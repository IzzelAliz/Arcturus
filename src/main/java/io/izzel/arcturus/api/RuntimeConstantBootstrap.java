package io.izzel.arcturus.api;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.Arrays;

record RuntimeConstantBootstrap(int bsmArgs, Intrinsic.IntrinsicFactory factory) implements Intrinsic.IntrinsicFactory {

    @Override
    public CallSite create(MethodHandles.Lookup lookup, MethodType methodType, Object... args) {
        var callSite = new LazyCallSite(this, lookup, methodType, args);
        callSite.initialize();
        return callSite;
    }

    private static class LazyCallSite extends MutableCallSite {

        private static final MethodHandle H_INITIALIZE;

        static {
            try {
                H_INITIALIZE = MethodHandles.lookup().findVirtual(LazyCallSite.class, "initializeHandler", MethodType.methodType(Object.class, Object[].class));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private final RuntimeConstantBootstrap delegate;
        private final MethodHandles.Lookup lookup;
        private final MethodType methodType;
        private final Object[] constantArgs;

        public LazyCallSite(RuntimeConstantBootstrap delegate, MethodHandles.Lookup lookup, MethodType methodType, Object[] constantArgs) {
            super(methodType);
            this.delegate = delegate;
            this.lookup = lookup;
            this.methodType = methodType;
            this.constantArgs = constantArgs;
        }

        public Object initializeHandler(Object... args) throws Throwable {
            var argCount = delegate.bsmArgs;
            var bootstrapArgs = Arrays.copyOf(constantArgs, constantArgs.length + argCount);
            System.arraycopy(args, 0, bootstrapArgs, constantArgs.length, argCount);
            var callSite = delegate.factory.create(lookup, methodType.dropParameterTypes(0, argCount), bootstrapArgs);
            var handle = callSite.dynamicInvoker();
            var newTarget = MethodHandles.dropArguments(handle, 0, methodType.parameterList().subList(0, argCount));
            this.setTarget(newTarget);
            MutableCallSite.syncAll(new MutableCallSite[]{this});
            return handle.invokeWithArguments(Arrays.copyOfRange(args, argCount, args.length));
        }

        void initialize() {
            var methodHandle = H_INITIALIZE.bindTo(this).withVarargs(true).asType(methodType);
            this.setTarget(methodHandle);
            MutableCallSite.syncAll(new MutableCallSite[]{this});
        }
    }
}
