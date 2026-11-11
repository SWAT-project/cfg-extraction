import org.sosy_lab.sv_benchmarks.ObjectFactory;
import org.sosy_lab.sv_benchmarks.Verifier;

// A callback that may reach an assert: every library call becomes an assertion point
// (CallbackTargets), except those that cannot call back, such as SWAT's helpers and the boxing and
// new String(literal) SWAT's preparation adds. Also nondetObject, Runtime.halt and a library
// exception with a message (see PreparedClassesSpec).
public class Main {
  static class Item {
    int v;
    Item(int v) { this.v = v; }
    @Override
    public String toString() {
      assert v != 13;
      return "item";
    }
  }

  static class ItemFactory implements ObjectFactory<Item> {
    public Item createObject() { return new Item(Verifier.nondetInt()); }
  }

  public static void main(String[] args) {
    int n = Verifier.nondetInt();
    Item item = Verifier.nondetObject(Item.class, new ItemFactory());
    String label = "label";
    Integer boxed = n;
    StringBuilder sb = new StringBuilder();
    sb.append(label);
    sb.append(item);
    if (item == null) Runtime.getRuntime().halt(0);
    if (sb.length() > 3) System.out.println(boxed);
    if (n == 3) throw new IllegalArgumentException("three");
  }
}
