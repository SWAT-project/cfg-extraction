// Smoke test for reference comparisons.
//
// Compile and extract with:
//   javac -d target/ target/RefEquality.java
//   ./gradlew run --args="target/ output/ RefEquality main inter"
//
// SWAT rewrites every IF_ACMPEQ / IF_ACMPNE into a call to UtilInstrumented.refEquals(a, b), which is
// itself instrumented and reports one or two branches of its own before the comparison does. So
// `a != b` below must be preceded by two RefEqualityCheck nodes (refeq_*), while the null checks,
// which compile to IFNULL / IFNONNULL and are not rewritten, must not.
public class RefEquality {

    enum Level { LOW, HIGH }

    static Level level(int n) {
        return n > 3 ? Level.HIGH : Level.LOW;
    }

    public static void main(String[] args) {
        Level a = level(args.length);
        if (a != Level.HIGH) {
            return;
        }
        Object o = args.length > 5 ? null : a;
        if (o == null) {
            return;
        }
        assert false;
    }
}
