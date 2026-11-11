package de.uzl.its.cfg.loops

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.Opcodes
import spock.lang.Specification

import java.nio.file.Files

/**
 * Loop facts carried in the class files (LoopFactsAnnotations): the executor must read back exactly
 * the facts it would compute, and nothing else in the class may change.
 */
class LoopFactsAnnotationsSpec extends Specification {

    static byte[] bytes(Class<?> c) {
        c.classLoader.getResourceAsStream(c.name.replace('.', '/') + '.class').withCloseable { it.bytes }
    }

    static ClassNode node(byte[] b) {
        def cn = new ClassNode()
        new ClassReader(b).accept(cn, 0)
        cn
    }

    def "every method with code reads back the facts it would get computed (#c.simpleName)"() {
        given:
        int[] counts = new int[2]
        def annotated = node(LoopFactsAnnotations.annotate(bytes(c), counts))

        expect:
        def expected = LoopFacts.analyzeClass(node(bytes(c)))
        annotated.methods.findAll { it.instructions.size() > 0 }.every { mn ->
            LoopFactsAnnotations.read(mn) == expected[LoopFacts.key(annotated.name, mn.name, mn.desc)]
        }
        counts[0] == expected.size()
        counts[1] == expected.values().count { !it.isEmpty() }
        counts[1] > 0

        where:
        c << LoopFactsSpec.CLASSES
    }

    def "the code is unchanged, and annotating twice gives the same class"() {
        given:
        byte[] original = bytes(LoopFixtures)
        byte[] once = LoopFactsAnnotations.annotate(original, new int[2])
        byte[] twice = LoopFactsAnnotations.annotate(once, new int[2])

        expect:
        twice == once
        def a = node(original), b = node(once)
        a.methods.size() == b.methods.size()
        [a.methods, b.methods].transpose().every { m1, m2 -> LoopFacts.hash(m1) == LoopFacts.hash(m2) }
    }

    def "facts of a method whose code changed are still read, and their hash tells"() {
        given:
        def cn = node(LoopFactsAnnotations.annotate(bytes(LoopFixtures), new int[2]))
        def mn = cn.methods.find { LoopFactsAnnotations.read(it) != null && !LoopFactsAnnotations.read(it).isEmpty() }

        when:
        mn.instructions.insert(new InsnNode(Opcodes.NOP))

        then:
        LoopFactsAnnotations.read(mn).hash() != LoopFacts.hash(mn)
    }

    def "a method without the annotation has none"() {
        expect:
        node(bytes(LoopFixtures)).methods.every { LoopFactsAnnotations.read(it) == null }
    }

    def "a directory is annotated in place"() {
        given:
        def dir = Files.createTempDirectory("loop-facts-annotations")
        def f = dir.resolve("LoopFixtures.class")
        Files.write(f, bytes(LoopFixtures))

        when:
        int[] counts = LoopFactsAnnotations.annotateDirectory(dir)

        then:
        counts[0] == 1
        counts[2] > 0
        node(Files.readAllBytes(f)).methods.any { LoopFactsAnnotations.read(it) != null }
        Files.list(dir).count() == 1

        cleanup:
        dir.toFile().deleteDir()
    }
}
