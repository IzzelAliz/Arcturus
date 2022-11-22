package io.izzel.arcturus.processor;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

@SuppressWarnings("all")
class Unsafe {

    private static final sun.misc.Unsafe unsafe;
    private static final MethodHandles.Lookup lookup;

    static {
        try {
            MethodHandles.lookup();
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            Object base = unsafe.staticFieldBase(field);
            long offset = unsafe.staticFieldOffset(field);
            lookup = (MethodHandles.Lookup) unsafe.getObject(base, offset);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void exportJdkModule() {
        try {
            var module = ModuleLayer.boot().findModule("jdk.compiler").orElseThrow();
            var method = Module.class.getDeclaredMethod("implAddExportsToAllUnnamed", String.class);
            var handle = lookup().unreflect(method).bindTo(module);
            for (var pkg : module.getPackages()) {
                handle.invokeExact(pkg);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static MethodHandles.Lookup lookup() {
        return lookup;
    }

    public static sun.misc.Unsafe getUnsafe() {
        return unsafe;
    }
}
