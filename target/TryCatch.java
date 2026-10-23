public class TryCatch {
    public static void main(String[] args) {
        test(1);
    }

    public static void test(int a) {
        int[] arr = new int[4];
        try {
            arr[a] = 0;

            System.out.println(4);
        } catch (Exception e) {
            assert false;
        }
    }
}
