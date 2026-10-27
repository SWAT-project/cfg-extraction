package de.uzl.its.cfg.staticcheck

import spock.lang.Specification
import spock.lang.TempDir

import javax.tools.ToolProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * The per-site facts the static pre-analysis graph uses: which instructions cannot raise a
 * RuntimeException, named by branch key (guards) or by method, line and kind (statement sites).
 * A site named safe that can raise would let SWAT prune a path to a violation.
 */
class SiteFactsSpec extends Specification {

    @TempDir
    Path dir

    /**
     * Compiles Main (one statement per line where it matters) and returns its site facts. The
     * import line put in front shifts every line of the given source by one.
     */
    SiteFacts facts(String main) {
        Path src = dir.resolve('src'), out = dir.resolve('classes')
        Files.createDirectories(src.resolve('org/sosy_lab/sv_benchmarks'))
        Files.createDirectories(out)
        Files.writeString(src.resolve('org/sosy_lab/sv_benchmarks/Verifier.java'), ExceptionFreedomCheckSpec.VERIFIER)
        Files.writeString(src.resolve('org/sosy_lab/sv_benchmarks/ObjectFactory.java'), ExceptionFreedomCheckSpec.FACTORY)
        Files.writeString(src.resolve('Main.java'), 'import org.sosy_lab.sv_benchmarks.Verifier;\n' + main)
        def files = []
        src.toFile().eachFileRecurse { if (it.name.endsWith('.java')) files << it.path }
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                (['--release', '8', '-g', '-nowarn', '-d', out.toString()] + files) as String[])
        assert rc == 0
        return ExceptionFreedomCheck.siteFacts([out])
    }

    static final String MAIN = 'Main.main([Ljava/lang/String;)V'

    def "guards are named by their branch key"() {
        when:
        def f = facts('''public class Main {
  public static void main(String[] a) {
    int[] arr = new int[3];
    arr[1] = 2;
    arr[Verifier.nondetInt()] = 1;
    int q = 10 / 2;
    int r = 10 / Verifier.nondetInt();
  }
}''')

        then:
        f.world == 'closed'
        f.guardIsSafe("$MAIN|4|newarr|0")
        f.guardIsSafe("$MAIN|5|arr|0")
        !f.guardIsSafe("$MAIN|6|arr|0")
        // javac folds 10 / 2, so line 7 has no division
        !f.guardIsSafe("$MAIN|8|div|0")
    }

    def "statement sites are grouped by method, line and kind"() {
        when:
        def f = facts('''class B { int x; void g() {} }
public class Main {
  static B maybe() { return Verifier.nondetBoolean() ? new B() : null; }
  public static void main(String[] a) {
    B b = new B();
    b.x = 1;
    B c = maybe();
    c.x = 2;
    b.g();
    c.g();
    Object o = b;
    B d = (B) o;
    throw new RuntimeException();
  }
}''')

        then:
        f.siteIsSafe(MAIN, 7, 'putfield:x')
        !f.siteIsSafe(MAIN, 9, 'putfield:x')
        f.siteIsSafe(MAIN, 10, 'call:g()V')
        !f.siteIsSafe(MAIN, 11, 'call:g()V')
        f.siteIsSafe(MAIN, 13, 'cast')
        !f.siteIsSafe(MAIN, 14, 'throw')
        f.sitesOn(MAIN, 7) == ['putfield:x']
    }

    def "a group with one site that may raise is not safe"() {
        when:
        def f = facts('''class B { int x; }
public class Main {
  static B maybe() { return Verifier.nondetBoolean() ? new B() : null; }
  public static void main(String[] a) {
    B b = new B(); B c = maybe();
    int s = b.x + c.x;
  }
}''')

        then:
        f.sitesOn(MAIN, 7) == ['getfield:x', 'getfield:x']
        !f.siteIsSafe(MAIN, 7, 'getfield:x')
    }

    def "a static call is safe even if its callee throws: the callee's sites are its own"() {
        when:
        def f = facts('''public class Main {
  static void boom() { throw new IllegalStateException(); }
  public static void main(String[] a) {
    boom();
  }
}''')

        then:
        f.siteIsSafe(MAIN, 5, 'call:boom()V')
        !f.siteIsSafe('Main.boom()V', 3, 'throw')
    }

    def "a throw of a checked exception cannot raise a RuntimeException"() {
        when:
        def f = facts('''public class Main {
  public static void main(String[] a) throws Exception {
    throw new Exception();
  }
}''')

        then:
        f.siteIsSafe(MAIN, 4, 'throw')
    }

    def "in an open world only what a method shows itself is used"() {
        when:
        // System.getenv is not modelled: the world is not closed, so the parameter fact (always a
        // new B) is not used.
        def f = facts('''class B { int x; }
public class Main {
  static void set(B b) {
    b.x = 1;
  }
  public static void main(String[] a) {
    System.getenv("X");
    set(new B());
    B own = new B();
    own.x = 2;
  }
}''')

        then:
        f.world == 'local'
        !f.siteIsSafe('Main.set(LB;)V', 5, 'putfield:x')
        f.siteIsSafe(MAIN, 11, 'putfield:x')
    }

    def "under a closed world the facts of reachable methods are used, local ones elsewhere"() {
        when:
        def f = facts('''class B { int x; }
public class Main {
  static void set(B b) {
    b.x = 1;
  }
  static void never(B b) {
    b.x = 1;
  }
  public static void main(String[] a) {
    set(new B());
  }
}''')

        then:
        f.world == 'closed'
        f.siteIsSafe('Main.set(LB;)V', 5, 'putfield:x')
        // never is not reachable: its parameter fact (no value) would make everything safe
        !f.siteIsSafe('Main.never(LB;)V', 8, 'putfield:x')
    }

    def "the receiver of an instance method is never null"() {
        when:
        def f = facts('''class B {
  int x;
  void set() {
    this.x = 1;
  }
}
public class Main {
  public static void main(String[] a) {
    System.getenv("X");
  }
}''')

        then:
        f.siteIsSafe('B.set()V', 5, 'putfield:x')
    }

    def "invokedynamic is never safe"() {
        when:
        def f = facts('''public class Main {
  public static void main(String[] a) {
    Runnable r = () -> {};
  }
}''')

        then:
        f.sitesOn(MAIN, 4).any { it.startsWith('call:run') }
        !f.siteIsSafe(MAIN, 4, f.sitesOn(MAIN, 4).find { it.startsWith('call:run') })
    }
}
