package io.izzel.arcturus.test;

import io.izzel.arcturus.api.Tailrec;

public class TailCallTest {

    public static void main(String[] args) {
        System.out.println(fib(6, 0));
        System.out.println(fact(4, 1));
        System.out.println(fact2(4, 1));
        System.out.println(fact3(4, 1));
        System.out.println(fact4(4, 1));
        System.out.println(fact5(4, 1));
    }

    @Tailrec
    private static int fib(int n, int acc) {
        if (n < 2) {
            return n + acc;
        } else {
            return fib(n - 1, // this is tail call
                fib(n - 2, acc) // but this is not
            );
        }
    }

    @Tailrec
    private static int fact(int i, int acc) {
        if (i > 1) return fact(i - 1, i * acc);
        return acc;
    }

    @Tailrec
    private static int fact2(int i, int acc) {
        return i > 1 ? fact2(i - 1, i * acc) : acc;
    }

    @Tailrec
    private static int fact3(int i, int acc) {
        switch (i) {
            case 0, 1:
                return acc;
            default:
                return fact3(i - 1, i * acc);
        }
    }

    @Tailrec
    private static int fact4(int i, int acc) {
        return switch (i) {
            case 0, 1 -> acc;
            default -> fact4(i - 1, i * acc);
        };
    }

    @Tailrec
    private static int fact5(int i, int acc) {
        return switch (i) {
            case 0, 1 -> acc;
            default -> i > -1 ? fact5(i - 1, i * acc) : 1;
        };
    }
}
