// Precision case for context-sensitive marking.
//
// Compile and extract with:
//   javac -d target/ target/SharedCalleePrecision.java
//   ./gradlew run --args="target/ output/ SharedCalleePrecision main inter"
//
// classify() is called from two sites. After the first one the program returns without reaching
// an assert; after the second one it asserts on the result. Marked context-insensitively, the
// branch inside classify() is interesting for both calls, because *some* caller leads to the
// assert. Marked context-sensitively it is interesting only when the call stack says the walk
// entered classify() from the second call site.
public class SharedCalleePrecision {

    static int classify(int x) {
        if (x > 10) {
            return 1;
        }
        return 0;
    }

    public static void main(String[] args) {
        int n = args.length;
        if (n > 3) {
            classify(n);
            return;
        }
        int c = classify(n + 20);
        assert c == 0;
    }
}
