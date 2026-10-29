/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.invoke;

import dalvik.system.EmulatedStackFrame;
import dalvik.system.EmulatedStackFrame.StackFrameReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/*
 * Android desugars lambdas with D8, so its LambdaMetafactory is a stub that
 * returns null. art_standalone compiles libcore with dx, which keeps the
 * invokedynamic of libcore's own lambdas (e.g. in java.time), so they need a
 * working metafactory. This one builds each lambda as a java.lang.reflect.Proxy
 * that calls the implementation method through its MethodHandle.
 */
public class LambdaMetafactory {

    public static final int FLAG_SERIALIZABLE = 1 << 0;

    public static final int FLAG_MARKERS = 1 << 1;

    public static final int FLAG_BRIDGES = 1 << 2;

    public static CallSite metafactory(MethodHandles.Lookup caller,
                                       String invokedName,
                                       MethodType invokedType,
                                       MethodType samMethodType,
                                       MethodHandle implMethod,
                                       MethodType instantiatedMethodType)
            throws LambdaConversionException {
        return makeCallSite(caller, invokedName, invokedType, implMethod, new Class<?>[0]);
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller,
                                          String invokedName,
                                          MethodType invokedType,
                                          Object... args)
            throws LambdaConversionException {
        // args: samMethodType, implMethod, instantiatedMethodType, flags,
        // [marker count, markers...], [bridge count, bridges...]
        MethodHandle implMethod = (MethodHandle) args[1];
        int flags = (Integer) args[3];
        Class<?>[] markers = new Class<?>[0];
        if ((flags & FLAG_MARKERS) != 0) {
            int count = (Integer) args[4];
            markers = Arrays.copyOfRange(args, 5, 5 + count, Class[].class);
        }
        // bridges share the method name, which is all the proxy dispatches on
        return makeCallSite(caller, invokedName, invokedType, implMethod, markers);
    }

    private static CallSite makeCallSite(MethodHandles.Lookup caller, String samName,
                                         MethodType invokedType, MethodHandle implMethod,
                                         Class<?>[] markers)
            throws LambdaConversionException {
        Class<?>[] interfaces = new Class<?>[markers.length + 1];
        interfaces[0] = invokedType.returnType();
        System.arraycopy(markers, 0, interfaces, 1, markers.length);
        try {
            Class<?> proxyClass = Proxy.getProxyClass(caller.lookupClass().getClassLoader(), interfaces);
            LambdaFactory factory = new LambdaFactory(samName, implMethod,
                    proxyClass.getConstructor(InvocationHandler.class));
            if (invokedType.parameterCount() == 0) {
                // a lambda that captures nothing is one shared instance, as on the JVM
                return new ConstantCallSite(MethodHandles.constant(interfaces[0], factory.newInstance(new Object[0])));
            }
            return new ConstantCallSite(new Capture(invokedType, factory));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new LambdaConversionException(e);
        }
    }

    private static final class LambdaFactory {
        private final String samName;
        private final MethodHandle implMethod;
        private final MethodHandle implInvoker;
        private final Constructor<?> proxyConstructor;

        LambdaFactory(String samName, MethodHandle implMethod, Constructor<?> proxyConstructor) {
            this.samName = samName;
            this.implMethod = implMethod;
            this.proxyConstructor = proxyConstructor;
            // invokeWithArguments needs the type's MethodTypeForm, which the
            // MethodTypes the runtime creates for call sites lack; an equal
            // type made here has one
            MethodType type = implMethod.type();
            implInvoker = MethodHandles.spreadInvoker(
                    MethodType.methodType(type.returnType(), type.parameterArray()), 0);
        }

        Object newInstance(Object[] captured) throws ReflectiveOperationException {
            return proxyConstructor.newInstance(new LambdaHandler(this, captured));
        }
    }

    /*
     * The call site's target. The runtime requires its real type to be the
     * invokedType, which adapters from asType() do not change, so this reads
     * the captured values from the frame itself.
     */
    private static final class Capture extends Transformers.Transformer {
        private final LambdaFactory factory;

        Capture(MethodType invokedType, LambdaFactory factory) {
            super(invokedType);
            this.factory = factory;
        }

        @Override
        protected void transform(EmulatedStackFrame frame) throws Throwable {
            StackFrameReader reader = new StackFrameReader();
            reader.attach(frame);
            Class<?>[] types = type().parameterArray();
            Object[] captured = new Object[types.length];
            for (int i = 0; i < types.length; i++)
                captured[i] = readBoxed(reader, types[i]);
            frame.setReturnValueTo(factory.newInstance(captured));
        }

        private static Object readBoxed(StackFrameReader reader, Class<?> type) {
            if (!type.isPrimitive())
                return reader.nextReference(type);
            if (type == int.class)
                return reader.nextInt();
            if (type == long.class)
                return reader.nextLong();
            if (type == boolean.class)
                return reader.nextBoolean();
            if (type == char.class)
                return reader.nextChar();
            if (type == byte.class)
                return reader.nextByte();
            if (type == short.class)
                return reader.nextShort();
            if (type == float.class)
                return reader.nextFloat();
            return reader.nextDouble();
        }
    }

    private static final class LambdaHandler implements InvocationHandler {
        private final LambdaFactory factory;
        private final Object[] captured;

        LambdaHandler(LambdaFactory factory, Object[] captured) {
            this.factory = factory;
            this.captured = captured;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args == null)
                args = new Object[0];
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return proxy.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
                }
            }
            if (method.isDefault()) {
                // lookups from java.lang.invoke are refused, an accessible Method is not checked
                method.setAccessible(true);
                return MethodHandles.publicLookup().unreflectSpecial(method, method.getDeclaringClass())
                        .bindTo(proxy).invokeWithArguments(args);
            }
            if (!method.getName().equals(factory.samName))
                throw new AbstractMethodError(method.toString());
            Object[] all = new Object[captured.length + args.length];
            System.arraycopy(captured, 0, all, 0, captured.length);
            System.arraycopy(args, 0, all, captured.length, args.length);
            return factory.implInvoker.invoke(factory.implMethod, all);
        }
    }
}
