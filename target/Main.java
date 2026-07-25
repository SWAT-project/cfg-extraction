public class Main {
    public static void main(String[] var0) {
        test(1, 2, 3);

        // doNothing();
        // recurse(0);
        deep0();
    }

    public static int test(int var0, int var1, int var2) {
        if (var0 < 6) {
            if (var1 < 0) {
                return var2;
            }

            if (var1 < var2) {
                ++var1;
            }
        } else {
            assert var1 >= 14 || var2 == 0;
        }

        return var1;
    }

    public static void doNothing() {
    }

    public static void recurse(int i) {
        if (i < 20) {
            recurse(i + 1);
        }
    }

    public static void deep0() {
        deep1();
    }

    public static void deep1() {
        deep2();
    }

    public static void deep2() {
        deep3();
    }

    public static void deep3() {
        deep4();
    }

    public static void deep4() {
        deep5();
    }

    public static void deep5() {
        deep6();
    }

    public static void deep6() {
        assert false;
    }
}
