public class TryCatch {
    public static void main(String[] args) {
        test(1);
    }

    public static void test(int a) {
        try {
            if (a < 0) {
                throw new Exception();
            } else {
                System.out.println(4);
            }
        } catch (Exception e) {
            assert false;
        }
    }
}
