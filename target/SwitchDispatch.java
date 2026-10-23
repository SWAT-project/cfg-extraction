// Smoke test for switch expansion.
//
// SWAT does not branch on a switch in one step: its visitor tests the cases one at a time and
// reports a branch for each test it performs, stopping at the first match. The graph has to spell
// out the same tests in the same order, or the branch counts drift apart.
//
// Two shapes matter, because javac picks a different bytecode instruction for each:
//   * dense() compiles to TABLESWITCH, which SWAT walks over min..max *inclusive* -- so the gap
//     at 3 is tested too, even though no case names it. Four tests, not three.
//   * sparse() compiles to LOOKUPSWITCH, which SWAT walks over the key array in order.
//
// Compile and extract with:
//   javac -d target/ target/SwitchDispatch.java
//   ./gradlew run --args="target/ output/ SwitchDispatch main inter"
public class SwitchDispatch {

    static int dense(int x) {
        switch (x) {
            case 1: return 10;
            case 2: return 20;
            // no case 3 -- TABLESWITCH still tests it and falls to default
            case 4: return 40;
            default: return -1;
        }
    }

    static int sparse(int x) {
        switch (x) {
            case 7: return 70;
            case 900: return 900;
            default: return -1;
        }
    }

    public static void main(String[] args) {
        int n = args.length;
        assert dense(n) != 40;
        assert sparse(n) != 70;
    }
}
