package de.uzl.its.cfg.extractor

import de.uzl.its.cfg.staticcheck.ExceptionFreedomCheck
import de.uzl.its.cfg.staticcheck.ExceptionFreedomCheckSpec
import de.uzl.its.cfg.staticcheck.SwatHelpers
import com.google.gson.Gson
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import spock.lang.Shared
import spock.lang.Specification

import javax.tools.ToolProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The extractor on prepared classes: SWAT's semantic rewrites applied ahead of time (SWAT's
 * de.uzl.its.swat.prepare.Prepare), which is the code SWAT's executor runs and reports branches
 * for. The graph of a prepared program must have the same branches, keys, assertion points and
 * runtime-exception points as that of the original, and model SWAT's helpers (SwatHelpers).
 *
 * <p>The fixtures under src/test/resources/prepared/<name> are {@code src/Main.java}, the classes
 * javac made of it ({@code original}, javac --release 8 -g against sv-benchmarks' common/) and what
 * SWAT's preparation made of those ({@code prepared}, sv-comp.cfg). Regenerate both together when
 * SWAT's rewrites change; the Verifier is compiled here from ExceptionFreedomCheckSpec's model.
 */
class PreparedClassesSpec extends Specification {

    @Shared
    Path work

    @Shared
    Map<String, Map> graphs = [:]

    static Path resources = Paths.get(PreparedClassesSpec.getResource('/prepared').toURI())

    def setupSpec() {
        work = Files.createTempDirectory('prepared-spec')
        Path src = work.resolve('verifier-src'), out = work.resolve('common')
        Files.createDirectories(src)
        Files.createDirectories(out)
        Files.writeString(src.resolve('Verifier.java'), ExceptionFreedomCheckSpec.VERIFIER)
        Files.writeString(src.resolve('ObjectFactory.java'), ExceptionFreedomCheckSpec.FACTORY)
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                '--release', '8', '-g', '-nowarn', '-d', out.toString(),
                src.resolve('Verifier.java').toString(), src.resolve('ObjectFactory.java').toString())
        assert rc == 0
    }

    def cleanupSpec() {
        work?.toFile()?.deleteDir()
    }

    List<Path> classpath(String fixture, String variant) {
        [work.resolve('common'), resources.resolve(fixture).resolve(variant)]
    }

    /** The inter-procedural graph and the loop facts of a fixture, extracted once. */
    Map graph(String fixture, String variant) {
        graphs.computeIfAbsent(fixture + '/' + variant) {
            Path out = work.resolve('out').resolve(fixture).resolve(variant)
            de.uzl.its.cfg.Main.main([classpath(fixture, variant).join(File.pathSeparator), out.toString(),
                                      'Main', 'main', 'inter'] as String[])
            def g = json(out.resolve('Main_main_interprocedural.json'))
            g.loopFacts = json(out.resolve('loop_facts.json'))
            g
        }
    }

    static Map json(Path file) {
        new Gson().fromJson(Files.readString(file), Map)
    }

    static List<Map> branches(Map g) {
        g.nodes.findAll { it.stmtType in ['JIfStmt', 'PhantomGuard', 'SwitchCase'] }
    }

    /** The nodes of {@code ids} named by method, line and statement type, as a sorted list. */
    static List<String> named(Map g, List ids) {
        def byId = g.nodes.collectEntries { [it.id, it] }
        ids.collect { byId[it].metadata + '|' + (byId[it].lineNumber as int) + '|' + byId[it].stmtType }.sort()
    }

    static int lineOf(String fixture, String text) {
        def lines = Files.readAllLines(resources.resolve(fixture).resolve('src/Main.java'))
        def i = lines.findIndexOf { it.contains(text) }
        assert i >= 0: text
        i + 1
    }

    def "the fixtures are what SWAT's preparation makes"() {
        when:
        def cn = new ClassNode()
        new ClassReader(Files.readAllBytes(resources.resolve('shapes/prepared/Main.class'))).accept(cn, 0)
        def calls = cn.methods.find { it.name == 'main' }.instructions.findAll { it instanceof MethodInsnNode }
                .collect { MethodInsnNode m -> m.owner + '.' + m.name }

        then:
        cn.invisibleAnnotations*.desc.contains('Lde/uzl/its/swat/Prepared;')
        calls.containsAll(['de/uzl/its/swat/common/UtilInstrumented.refEquals', 'de/uzl/its/swat/common/UtilInstrumented.intern',
                           'de/uzl/its/swat/instrument/svcomp/Verifier.nondetInt', 'de/uzl/its/swat/instrument/svcomp/Verifier.assume',
                           'de/uzl/its/swat/instrument/Intrinsics.liftValue', 'de/uzl/its/swat/witness/Witness.recordValue',
                           'de/uzl/its/swat/witness/Witness.recordThrow', 'de/uzl/its/swat/common/RefIdentity.markDeInterned',
                           'de/uzl/its/swat/instrument/svcomp/SystemExit.exit', 'java/lang/Integer.<init>'])
        !calls.contains('org/sosy_lab/sv_benchmarks/Verifier.nondetInt')
    }

    def "branches and their keys are the same on original and prepared classes (#fixture)"() {
        given:
        def original = graph(fixture, 'original')
        def prepared = graph(fixture, 'prepared')

        expect:
        branches(prepared).size() == branches(original).size()
        branches(prepared).collect { it.key }.sort() == branches(original).collect { it.key }.sort()
        branches(prepared).every { it.key != null }
        // the inserted dup and the calls before athrow add no branch, the helpers are leaf calls
        prepared.edges.count { it.type == 'CALL' } == original.edges.count { it.type == 'CALL' }

        where:
        fixture << ['shapes', 'callback']
    }

    def "a reference comparison keeps an exact ordinal next to a boolean test on its line"() {
        given:
        def line = lineOf('shapes', 'if (flag && a == b)')
        def keys = branches(graph('shapes', 'prepared')).findAll { (it.lineNumber as int) == line }.collect { it.key }

        expect:
        keys.sort() == ["Main.main([Ljava/lang/String;)V|$line|if|0", "Main.main([Ljava/lang/String;)V|$line|if|1"]*.toString()
    }

    def "assertion points, runtime-exception points and terminators are the same (#fixture)"() {
        given:
        def original = graph(fixture, 'original')
        def prepared = graph(fixture, 'prepared')

        expect:
        named(prepared, prepared.metadata.assertionPointIds) == named(original, original.metadata.assertionPointIds)
        named(prepared, prepared.metadata.runtimeExceptionPointIds) == named(original, original.metadata.runtimeExceptionPointIds)
        prepared.metadata.siteFacts == original.metadata.siteFacts
        prepared.metadata.siteFacts == world
        prepared.nodes.count { it.terminates } == original.nodes.count { it.terminates }
        (prepared.metadata.callbacksReachingAssert != null) == callbacks
        (original.metadata.callbacksReachingAssert != null) == callbacks

        where:
        fixture    | world    | callbacks
        'shapes'   | 'closed' | false
        'callback' | 'local'  | true
    }

    def "SWAT's helpers carry the data-flow facts of what they stand for"() {
        given:
        def g = graph('shapes', 'prepared')
        def calls = { String name -> g.nodes.findAll { it.statement.contains(name) } }

        expect:
        calls('instrument.svcomp.Verifier: int nondetInt').every { it.dataflow.any { pair -> 'g:input' in pair[0] && 'g:input' in pair[1] } }
        calls('instrument.svcomp.Verifier: void assume').every { it.terminates && it.control }
        calls('SystemExit: void exit').every { it.terminates }
        ['Witness: void recordValue', 'Witness: void recordThrow', 'RefIdentity: void markDeInterned'].every { name ->
            !calls(name).isEmpty() && calls(name).every { it.dataflow == null }
        }
        calls('Intrinsics: int liftValue').every { node ->
            node.dataflow.size() == 1 && node.dataflow[0][1].every { it.startsWith('l:') }
        }
    }

    def "the loop facts of the prepared classes cover the code SWAT runs"() {
        given:
        def original = graph('shapes', 'original').loopFacts.methods['Main.main([Ljava/lang/String;)V']
        def prepared = graph('shapes', 'prepared').loopFacts.methods['Main.main([Ljava/lang/String;)V']

        expect:
        prepared.hash != original.hash // the original's facts do not apply to the code SWAT runs
        prepared.heads.size() == original.heads.size()
    }

    def "the pre-check comes to the same result on the prepared classes (#fixture)"() {
        when:
        def original = ExceptionFreedomCheck.check(classpath(fixture, 'original'))
        def prepared = ExceptionFreedomCheck.check(classpath(fixture, 'prepared'))

        then:
        prepared.exceptionFree == original.exceptionFree
        prepared.assertionFree == original.assertionFree
        ExceptionFreedomCheck.siteFacts(classpath(fixture, 'prepared')).world == world

        where:
        fixture    | world
        'shapes'   | 'closed'
        'callback' | 'local'
    }

    def "SWAT's helpers are told apart by owner, name and descriptor"() {
        expect:
        SwatHelpers.kindOf(owner, name, desc) == kind

        where:
        owner                                         | name             | desc                                       | kind
        'de/uzl/its/swat/instrument/svcomp/Verifier'  | 'nondetInt'      | '(J)I'                                     | SwatHelpers.Kind.NONDET
        'de/uzl/its/swat/instrument/svcomp/Verifier'  | 'nondetString'   | '(J)Ljava/lang/String;'                    | SwatHelpers.Kind.NONDET
        'de/uzl/its/swat/instrument/svcomp/Verifier'  | 'assume'         | '(Z)V'                                     | SwatHelpers.Kind.ASSUME
        'de/uzl/its/swat/instrument/svcomp/Verifier'  | 'assume'         | '(I)V'                                     | SwatHelpers.Kind.UNMODELLED
        'de/uzl/its/swat/instrument/svcomp/Verifier'  | 'retrieveInputs' | '()V'                                      | SwatHelpers.Kind.UNMODELLED
        'de/uzl/its/swat/instrument/svcomp/SystemExit'| 'exit'           | '(I)V'                                     | SwatHelpers.Kind.EXIT
        'de/uzl/its/swat/instrument/svcomp/SystemExit'| 'runtimeHalt'    | '(Ljava/lang/Runtime;I)V'                  | SwatHelpers.Kind.RUNTIME_EXIT
        'de/uzl/its/swat/witness/Witness'             | 'recordThrow'    | '(Ljava/lang/Throwable;)V'                 | SwatHelpers.Kind.BOOKKEEPING
        'de/uzl/its/swat/witness/Witness'             | 'recordValue'    | '(DLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)V' | SwatHelpers.Kind.BOOKKEEPING
        'de/uzl/its/swat/common/RefIdentity'          | 'markDeInterned' | '(Ljava/lang/Object;)V'                    | SwatHelpers.Kind.BOOKKEEPING
        'de/uzl/its/swat/instrument/Intrinsics'       | 'liftValue'      | '(DJ)D'                                    | SwatHelpers.Kind.IDENTITY
        'de/uzl/its/swat/instrument/Intrinsics'       | 'liftValue'      | '(Ljava/lang/String;J)Ljava/lang/String;'  | SwatHelpers.Kind.IDENTITY
        'de/uzl/its/swat/instrument/Intrinsics'       | 'liftValue'      | '(IJ)J'                                    | SwatHelpers.Kind.UNMODELLED
        'de/uzl/its/swat/common/UtilInstrumented'     | 'refEquals'      | '(Ljava/lang/Object;Ljava/lang/Object;)Z'  | SwatHelpers.Kind.REF_EQUALS
        'de/uzl/its/swat/common/UtilInstrumented'     | 'intern'         | '(Ljava/lang/String;)Ljava/lang/String;'   | SwatHelpers.Kind.INTERN
        'de/uzl/its/swat/instrument/svcomp/Socket'    | 'connect'        | '(Ljava/net/SocketAddress;)V'              | SwatHelpers.Kind.UNMODELLED
        'org/sosy_lab/sv_benchmarks/Verifier'         | 'nondetInt'      | '()I'                                      | SwatHelpers.Kind.UNMODELLED
    }
}
