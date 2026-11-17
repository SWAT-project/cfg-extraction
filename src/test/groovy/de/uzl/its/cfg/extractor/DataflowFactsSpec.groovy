package de.uzl.its.cfg.extractor

import de.uzl.its.cfg.staticcheck.ExceptionFreedomCheckSpec
import com.google.gson.Gson
import spock.lang.Shared
import spock.lang.Specification

import javax.tools.ToolProvider
import java.util.regex.Pattern
import java.nio.file.Files
import java.nio.file.Path

/**
 * The data-flow facts (DataflowFacts) that SWAT's relevance analysis slices with. A location
 * missing from a write set lets SWAT slice a branch that matters, which hides a violation.
 *
 * <p>Constructors: {@code $r = new T; specialinvoke $r.<init>(args)} defines the object {@code $r}
 * from the arguments. SWAT's preparation turns {@code Boolean.valueOf(f)}, {@code Integer.valueOf(k)}
 * and autoboxing into {@code new Boolean(f)} and {@code new Integer(k)}, and the extractor runs on
 * prepared classes, so {@code Boolean b = f; if (b) ...} reads {@code f} only through such a
 * constructor.
 */
class DataflowFactsSpec extends Specification {

    static final String MAIN = '''
import org.sosy_lab.sv_benchmarks.Verifier;

public class Main {
  public static void main(String[] args) {
    int x = Verifier.nondetInt();
    boolean f = x > 5;
    Boolean b = new Boolean(f);
    if (b.booleanValue()) assert false; // bool
    int k = x > 7 ? 1 : 2;
    Integer i = new Integer(k);
    if (i.intValue() == 1) assert false; // int
    StringBuilder sb = new StringBuilder();
    if (x > 9) sb.append('a');
    String s = new String(sb);
    if (s.length() == 1) assert false; // string
    char[] cs = new char[] { 'a' };
    if (x > 11) cs[0] = 'b';
    String t = new String(cs);
    if (t.charAt(0) == 'b') assert false; // chars
  }
}
'''

    @Shared
    Path work

    @Shared
    Map graph

    def setupSpec() {
        work = Files.createTempDirectory('dataflow-spec')
        Path src = work.resolve('src'), out = work.resolve('classes')
        Files.createDirectories(src)
        Files.createDirectories(out)
        Files.writeString(src.resolve('Verifier.java'), ExceptionFreedomCheckSpec.VERIFIER)
        Files.writeString(src.resolve('ObjectFactory.java'), ExceptionFreedomCheckSpec.FACTORY)
        Files.writeString(src.resolve('Main.java'), MAIN)
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                '--release', '8', '-g', '-nowarn', '-d', out.toString(),
                src.resolve('Verifier.java').toString(), src.resolve('ObjectFactory.java').toString(),
                src.resolve('Main.java').toString())
        assert rc == 0
        Path graphs = work.resolve('out')
        de.uzl.its.cfg.Main.main([out.toString(), graphs.toString(), 'Main', 'main', 'inter'] as String[])
        graph = new Gson().fromJson(Files.readString(graphs.resolve('Main_main_interprocedural.json')), Map)
    }

    def cleanupSpec() {
        work?.toFile()?.deleteDir()
    }

    static int lineOf(String text) {
        def i = MAIN.readLines().findIndexOf { it.contains(text) }
        assert i >= 0: text
        i + 1
    }

    List<Map> mainNodes() {
        graph.nodes.findAll { it.metadata == 'method: <Main: void main(java.lang.String[])>' }
    }

    /** The node of the constructor call {@code <owner: void <init>(params)>}, with its base and argument. */
    Map constructor(String owner, String params) {
        def pattern = Pattern.compile('specialinvoke (\\S+)\\.' + Pattern.quote("<$owner: void <init>($params)>") + '\\((\\S+)\\)')
        def found = mainNodes().findResults { node ->
            def m = pattern.matcher(node.statement as String)
            m.find() ? [node: node, base: 'l:' + m.group(1), arg: 'l:' + m.group(2)] : null
        }
        assert found.size() == 1: "$owner($params): $found"
        found[0]
    }

    /**
     * The locations a branch depends on, as SWAT's relevance analysis computes them (flow- and
     * context-insensitive): its control reads, and what every statement that writes one of them reads.
     */
    Set<String> dependencies(int line) {
        def branch = mainNodes().find { it.stmtType == 'JIfStmt' && (it.lineNumber as int) == line }
        assert branch?.control: "no branch on line $line"
        Set<String> relevant = new HashSet<>(branch.control)
        boolean changed = true
        while (changed) {
            changed = false
            for (node in mainNodes()) {
                for (pair in node.dataflow ?: []) {
                    if (pair[0].any { it in relevant || it == '*' }) {
                        changed |= relevant.addAll(pair[1])
                    }
                }
            }
        }
        relevant
    }

    def "a constructor defines the object from its arguments: new #owner(#params)"() {
        given:
        def call = constructor(owner, params)

        expect: 'the object reference the caller keeps is written, from the argument'
        call.node.dataflow.any { pair -> call.base in pair[0] && call.arg in pair[1] }

        and: 'library state stays written where it was'
        call.node.dataflow.any { pair -> 'g:lib' in pair[0] } == writesLibraryState

        where:
        owner                | params                    | writesLibraryState
        'java.lang.Boolean'  | 'boolean'                 | false // pure (PURE_CLASSES)
        'java.lang.Integer'  | 'int'                     | false // pure
        'java.lang.String'   | 'char[]'                  | false // pure (a primitive array)
        'java.lang.String'   | 'java.lang.StringBuilder' | true  // a heap call
    }

    def "a constructor given only values still writes library state and the object"() {
        given:
        def node = mainNodes().find { (it.statement as String).contains('<java.lang.StringBuilder: void <init>()>') }
        def base = 'l:' + ((node.statement as String) =~ /specialinvoke ([^.\s]+)\./)[0][1]

        expect:
        node.dataflow.any { pair -> 'g:lib' in pair[0] && base in pair[0] && base in pair[1] }
    }

    def "a branch on a boxed value depends on what was boxed (#marker)"() {
        given:
        def dependencies = dependencies(lineOf("// $marker"))

        expect: 'what the earlier branch writes in its region'
        dependencies.containsAll(written)

        where:
        marker   | written
        'bool'   | ['l:f']          // boolean f = x > 5; new Boolean(f)
        'int'    | ['l:k']          // int k = x > 7 ? 1 : 2; new Integer(k)
        'string' | ['g:lib', 'l:sb'] // if (x > 9) sb.append('a'); new String(sb)
        'chars'  | ['a:char']       // if (x > 11) cs[0] = 'b'; new String(cs)
    }
}
