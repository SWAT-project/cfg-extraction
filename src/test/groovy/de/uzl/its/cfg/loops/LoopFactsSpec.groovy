package de.uzl.its.cfg.loops

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LabelNode
import spock.lang.Specification

/**
 * The facts file must give the executor exactly what it would compute itself: the same facts, for
 * the same code however the class was read, at the same labels.
 */
class LoopFactsSpec extends Specification {

    static ClassNode read(Class<?> c, int flags) {
        def cn = new ClassNode()
        def name = c.name.replace('.', '/') + '.class'
        c.classLoader.getResourceAsStream(name).withCloseable { new ClassReader(it).accept(cn, flags) }
        cn
    }

    /** Classes with many loops: the fixtures, this library, ASM's reader and analyzer. */
    static final List<Class<?>> CLASSES = [LoopFixtures, LoopSummaries, TaintCheck, BodyAnalysis, LoopFactsJson,
                                           ClassReader, org.objectweb.asm.tree.analysis.Analyzer,
                                           org.objectweb.asm.MethodWriter, org.objectweb.asm.Frame]

    def "the fixtures have heads, diamonds and counting loops"() {
        when:
        def facts = LoopFacts.analyzeClass(read(LoopFixtures, 0))
        def plans = facts.values()*.plans()*.byJump()*.values().flatten()

        then:
        plans.any { it instanceof LoopSummaries.Diamond }
        plans.any { it instanceof LoopSummaries.CountingLoop }
        plans.any { it instanceof LoopSummaries.BackEdge }
        facts.values().any { it.heads().size() == 2 }
    }

    def "facts survive writing and reading, the back edge sharing its loop"() {
        given:
        Map<String, LoopFacts.MethodFacts> facts = [:]
        CLASSES.each { facts.putAll(LoopFacts.analyzeClass(read(it, 0))) }
        def out = new StringWriter()

        when:
        LoopFactsJson.write(facts, out)
        def back = LoopFactsJson.read(new StringReader(out.toString()))

        then:
        back == facts
        back.values().each { f ->
            f.plans().byJump().values().findAll { it instanceof LoopSummaries.BackEdge }.each { b ->
                assert f.plans().byJump().values().any { it.is(b.loop()) }
            }
        }
    }

    def "another version is ignored"() {
        expect:
        LoopFactsJson.read(new StringReader('{"version": 0, "methods": {}}')) == null
    }

    def "hash and facts do not depend on how the class was read"() {
        expect:
        CLASSES.each { c ->
            def plain = LoopFacts.analyzeClass(read(c, 0))
            [ClassReader.SKIP_FRAMES, ClassReader.EXPAND_FRAMES, ClassReader.SKIP_DEBUG].each { flags ->
                assert LoopFacts.analyzeClass(read(c, flags)) == plain
            }
        }
    }

    def "the hash changes with the code"() {
        given:
        def cn = read(LoopFixtures, 0)
        def m = cn.methods.find { it.name == 'counting' }
        def before = LoopFacts.hash(m)

        when:
        m.instructions.find { it.opcode == org.objectweb.asm.Opcodes.ICONST_0 }.with {
            m.instructions.set(it, new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_1))
        }

        then:
        LoopFacts.hash(m) != before
    }

    def "positions name the heads' and joins' labels"() {
        expect:
        CLASSES.each { c ->
            read(c, ClassReader.EXPAND_FRAMES).methods.each { mn ->
                def f = LoopFacts.analyze(c.name.replace('.', '/'), mn)
                def labels = Positions.labels(mn.instructions)
                f.heads().each { h ->
                    LabelNode l = labels[h.at()]
                    assert l != null
                    // a backward jump targets it
                    assert mn.instructions.any { it instanceof org.objectweb.asm.tree.JumpInsnNode && it.label.is(l) }
                }
                f.plans().joins().keySet().each { assert labels[it] != null }
            }
        }
    }
}
