// Smoke test for recursive calls, which are now expanded instead of being cut off.
//
// Compile and extract with:
//   javac -d target/ target/Recursion.java
//   ./gradlew run --args="target/ output/ Recursion main inter"
//
// Each method is in the graph once. A recursive call site gets a CALL edge back into the body it
// sits in, and the walk's call stack tells the recursion levels apart. Neither the direct
// recursion in fact() nor the mutual recursion between isEven() and isOdd() should leave an
// incomplete marker behind.
public class Recursion {

    static int fact(int n) {
        if (n <= 1) {
            return 1;
        }
        return n * fact(n - 1);
    }

    static boolean isEven(int n) {
        if (n == 0) {
            return true;
        }
        return isOdd(n - 1);
    }

    static boolean isOdd(int n) {
        if (n == 0) {
            return false;
        }
        return isEven(n - 1);
    }

    public static void main(String[] args) {
        int n = args.length;
        assert fact(n) != 6;
        assert !isOdd(n);
    }
}
