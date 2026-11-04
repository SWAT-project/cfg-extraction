package de.uzl.its.cfg.loops;

/**
 * Loops and diamonds the loop analyses summarize, compiled by javac (LoopFactsSpec). The merged
 * values only reach comparisons: returned, they would leave the method concrete (TaintCheck).
 */
public class LoopFixtures {

    static int counting(int n) {
        int s = 0, c = 7;
        for (int i = 0; i < n; i++) {
            s += 3;
            c = 2;
        }
        check(s + c == 30);
        return 0;
    }

    static int down(int n) {
        int s = 0;
        for (int i = n; i > 0; i--) {
            s += 2;
        }
        check(s == 8);
        return 0;
    }

    static int diamonds(int n, int v) {
        int count = 0, t = 0;
        for (int i = 0; i < n; i++) {
            if (i < v) {
                count++;
            }
            t = i < v ? t + 2 : t - 1;
            if (i > 3) {
                if (v > 2) {
                    count += 5;
                }
            }
        }
        check(count + t == 9);
        return 0;
    }

    static int heads(int[] a, long l, double d) {
        int k = 0;
        while (k < a.length && a[k] != 0) {
            k++;
            l += k;
            d *= 2;
        }
        do {
            k--;
        } while (k > 0);
        return k + (int) l + (int) d;
    }

    static int handler(int x) {
        int r = 0;
        for (int i = 0; i < x; i++) {
            try {
                r += 10 / (x - i);
            } catch (ArithmeticException e) {
                r = -1;
            }
        }
        return r;
    }

    static void check(boolean b) {
        if (b) {
            throw new IllegalStateException();
        }
    }
}
