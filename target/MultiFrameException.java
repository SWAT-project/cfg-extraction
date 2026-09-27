// Smoke test for an exception crossing several frames.
//
// Compile and extract with:
//   javac -d target/ target/MultiFrameException.java
//   ./gradlew run --args="target/ output/ MultiFrameException main inter"
//
// The ArithmeticException from inner()'s division escapes inner() and middle() -- middle() has a
// handler, but for an unrelated type -- and lands in main()'s handler, where the assert is.
// So:
//   * inner() has an uncaught[java.lang.ArithmeticException] node, which is not an exit node;
//   * its EXCEPTIONAL_RETURN edge goes to main()'s handler, not to middle()'s;
//   * the explicit throw in middle() of a freshly allocated IllegalStateException goes to
//     middle()'s own uncaught[java.lang.IllegalStateException] node and unwinds from there, to
//     main()'s RuntimeException handler.
public class MultiFrameException {

    static int inner(int d) {
        return 100 / d;
    }

    static int middle(int d) {
        try {
            if (d > 5) {
                throw new IllegalStateException();
            }
            return inner(d);
        } catch (UnsupportedOperationException e) {
            return -1;
        }
    }

    public static void main(String[] args) {
        int n = args.length;
        try {
            middle(n);
        } catch (ArithmeticException e) {
            assert false;
        } catch (RuntimeException e) {
            assert n == 7;
        }
    }
}
