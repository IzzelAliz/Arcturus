package io.izzel.arcturus.test;

import io.izzel.arcturus.api.Intrinsic;

import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public class CompileTest {

    public static void main(String[] args) {
        UltimateAnswer.main(args);
        System.out.println();
        // call on object instances
        int o = (int) new Test().testObject("", 2d);
        System.out.println(o);
        // call with bootstrap method argument constants
        // constant expression (JLS 15.29) required
        Intrinsic test = (Intrinsic) CompileTest.getstatic(CompileTest.class, "INTRINSIC");
        System.out.println(test);
        // call with runtime bsm argument
        Intrinsic test2 = (Intrinsic) CompileTest.altgetstatic(CompileTest.class, "INTRINSIC");
        System.out.println(test2);
        Intrinsic test3 = (Intrinsic) CompileTest.altgetstatic(CompileTest.class, "INTRINSIC");
        System.out.println(test3);
    }

    private static Intrinsic GET_STATIC() {
        return new Intrinsic("get_static", (lookup, methodType, args) -> {
            Class<?> type = (Class<?>) args[0];
            String field = (String) args[1];
            MethodHandle handle = lookup.unreflectGetter(type.getDeclaredField(field));
            return new ConstantCallSite(handle.asType(methodType));
        });
    }

    @Intrinsic.Link(value = "GET_STATIC", bsmArgs = 2)
    private static Object getstatic(Object... args) {
        throw new RuntimeException();
    }

    private static Intrinsic alt_GET_STATIC() {
        return new Intrinsic("alt_get_static", Intrinsic.IntrinsicFactory.runtimeBootstrapArgument(2, (lookup, methodType, args) -> {
            Class<?> type = (Class<?>) args[0];
            String field = (String) args[1];
            MethodHandle handle = lookup.unreflectGetter(type.getDeclaredField(field));
            return new ConstantCallSite(handle.asType(methodType));
        }));
    }

    @Intrinsic.Link(value = "alt_GET_STATIC")
    private static Object altgetstatic(Object... args) {
        throw new RuntimeException();
    }

    private static final Intrinsic INTRINSIC = UltimateAnswer.ANSWER;

    public static class Test {

        @Intrinsic.Link(value = "INTRINSIC", owner = CompileTest.class)
        Object testObject(Object... args) {
            throw new RuntimeException();
        }
    }
}

class UltimateAnswer {

    public static void main(String[] args) {
        // the "Ultimate." class name select pattern is essential due to javac restrictions
        int i = (int) UltimateAnswer.getAnswer();
        System.out.println(i);
    }

    static final Intrinsic ANSWER = new Intrinsic("test", (lookup, methodType, args) -> {
        MethodHandle handle = MethodHandles.dropArguments(MethodHandles.constant(int.class, 42), 0, methodType.parameterList());
        return new ConstantCallSite(handle);
    });

    @Intrinsic.Link("ANSWER")
    public static native Object getAnswer(Object... args);
}