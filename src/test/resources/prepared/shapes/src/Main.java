import org.sosy_lab.sv_benchmarks.Verifier;

// The shapes SWAT's preparation rewrites, in a program the pre-check sees as a closed world (see
// PreparedClassesSpec). Compiled with javac --release 8 -g; prepared with SWAT's
// de.uzl.its.swat.prepare.Prepare (sv-comp.cfg).
public class Main {
  static class Box {
    int v;
    Box(int v) { this.v = v; }
  }

  static Box shared = new Box(1);

  public static void main(String[] args) {
    int n = Verifier.nondetInt();
    boolean flag = Verifier.nondetBoolean();
    String s = Verifier.nondetString();
    Verifier.assume(n > -100 && n < 100);
    Box a = n > 0 ? shared : new Box(n);
    Box b = new Box(Verifier.nondetInt());
    if (flag && a == b) n++;
    if (a != shared) n--;
    String lit = "abc";
    if (s == lit) n += 2;
    String in = s.intern();
    if (in == lit.intern()) n += 3;
    Integer boxed = n;
    Integer other = 7;
    if (boxed == other) n += 4;
    int sum = 0;
    for (int i = 0; i < n; i++) sum += i;
    if (sum == 1000) System.exit(1);
    if (b.v == 3) throw new RuntimeException("three");
    assert sum != 45;
  }
}
