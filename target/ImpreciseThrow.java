// Smoke test for throws whose runtime type is not pinned down.
//
// Compile and extract with:
//   javac -d target/ target/ImpreciseThrow.java
//   ./gradlew run --args="target/ output/ ImpreciseThrow main inter"
//
// * choose() throws a Throwable local that holds one of two types which main() catches in
//   different handlers. The throw cannot be wired to one destination, so it is marked incomplete.
// * rethrow() throws its parameter, whose type is unknown altogether: incomplete as well.
// * exact() throws a Throwable local that only ever holds an Error. Its static type says nothing,
//   but the trace does, so it gets a single THROW edge to uncaught[java.lang.Error].
public class ImpreciseThrow {

    static void choose(int n) throws Throwable {
        Throwable t;
        if (n > 0) {
            t = new IllegalArgumentException();
        } else {
            t = new Error();
        }
        throw t;
    }

    static void rethrow(Throwable t) throws Throwable {
        throw t;
    }

    static void exact() throws Throwable {
        Throwable t = new Error();
        throw t;
    }

    public static void main(String[] args) {
        try {
            choose(args.length);
            rethrow(new RuntimeException());
            exact();
        } catch (RuntimeException e) {
            assert false;
        } catch (Throwable e) {
            assert args.length == 3;
        }
    }
}
