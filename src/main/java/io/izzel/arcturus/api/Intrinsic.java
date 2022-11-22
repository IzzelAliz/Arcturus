package io.izzel.arcturus.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * JEP 303
 */
public record Intrinsic(String name, IntrinsicFactory bsm) {

    public Object invoke(Object... args) {
        throw new AssertionError();
    }

    public static CallSite makeIntrinsic(MethodHandles.Lookup lookup, String methodName, MethodType methodType, MethodHandle intrinsicGetter, Object... bsmArgs) throws Throwable {
        Objects.requireNonNull(intrinsicGetter, "intrinsicGetter");
        var intrinsic = (Intrinsic) intrinsicGetter.asType(MethodType.methodType(Intrinsic.class)).invokeExact();
        Objects.requireNonNull(intrinsic, "intrinsic");
        var bsm = intrinsic.bsm();
        Objects.requireNonNull(bsm, "bsm");
        var name = intrinsic.name();
        Objects.requireNonNull(name, "name");
        try {
            return bsm.create(lookup, methodType, bsmArgs);
        } catch (Throwable t) {
            throw new BootstrapMethodError("Intrinsic " + name + " at " + methodName, t);
        }
    }

    public static Intrinsic withRuntimeBsmArgs(String name, int bsmArgs, IntrinsicFactory bsm) {
        return new Intrinsic(name, IntrinsicFactory.runtimeBootstrapArgument(bsmArgs, bsm));
    }

    @FunctionalInterface
    public interface IntrinsicFactory {

        CallSite create(MethodHandles.Lookup lookup, MethodType methodType, Object... args) throws Throwable;

        static IntrinsicFactory runtimeBootstrapArgument(int bsmArgs, IntrinsicFactory factory) {
            return new RuntimeConstantBootstrap(bsmArgs, factory);
        }
    }

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.METHOD)
    public @interface Link {

        String value();

        Class<?> owner() default Link.class;

        int bsmArgs() default 0;
    }
}
