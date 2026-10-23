
public class InterMethodCatch {
    public static void main(String[] args) {
        try {
            test(args.length);
        } catch (Exception e) {
            assert false;
        }
    }

    public static void test(int a) {
        int[] arr = new int[4];
        arr[a] = 0;
    }
}
