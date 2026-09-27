// Smoke test for a call chain deeper than the old depth limit of 5.
//
// Compile and extract with:
//   javac -d target/ target/DeepChain.java
//   ./gradlew run --args="target/ output/ DeepChain main inter"
//
// The assert sits eight calls down. It should be listed in metadata.assertionPointIds as a real
// assert, and nothing on the way should be a "DUMMY (depth limit)" node.
public class DeepChain {

    static void level1(int x) { level2(x + 1); }
    static void level2(int x) { level3(x + 1); }
    static void level3(int x) { level4(x + 1); }
    static void level4(int x) { level5(x + 1); }
    static void level5(int x) { level6(x + 1); }
    static void level6(int x) { level7(x + 1); }
    static void level7(int x) { level8(x + 1); }

    static void level8(int x) {
        if (x > 10) {
            assert false;
        }
    }

    public static void main(String[] args) {
        level1(args.length);
    }
}
