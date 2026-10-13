package io.github.zdcache.internal;

import java.io.Serializable;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

public final class Getters {
    private record Reference(String name, Class<?> type) {}

    private static final ClassValue<Reference[]> REFERENCES = new ClassValue<>() {
        @Override protected Reference[] computeValue(Class<?> type) {
            return new Reference[1];
        }
    };

    private Getters() { }

    public static String name(Serializable getter) {
        return reference(getter).name();
    }

    @SuppressWarnings("unchecked")
    public static <T> Class<T> type(Serializable getter) {
        return (Class<T>) reference(getter).type();
    }

    private static Reference reference(Serializable getter) {
        Reference[] slot = REFERENCES.get(getter.getClass());
        Reference reference = slot[0];
        if (reference == null) slot[0] = reference = derive(getter);
        return reference;
    }

    private static Reference derive(Serializable getter) {
        SerializedLambda lambda = serialized(getter);
        String method = lambda.getImplMethodName();
        if (method.startsWith("lambda$")) {
            throw new IllegalArgumentException("A dimension name can only be derived from a method reference such as "
                + "Person::name; pass the name explicitly when the extractor is a lambda");
        }
        ClassLoader loader = getter.getClass().getClassLoader();
        String name = isRecordComponent(lambda, method, loader) ? method : propertyName(method);
        return new Reference(name, receiverType(lambda, loader));
    }

    private static SerializedLambda serialized(Serializable getter) {
        try {
            Method writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            return (SerializedLambda) writeReplace.invoke(getter);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot read the method behind " + getter.getClass().getName()
                + "; pass the dimension name explicitly", e);
        }
    }

    private static Class<?> receiverType(SerializedLambda lambda, ClassLoader loader) {
        return MethodType.fromMethodDescriptorString(lambda.getInstantiatedMethodType(), loader).parameterType(0);
    }

    private static boolean isRecordComponent(SerializedLambda lambda, String method, ClassLoader loader) {
        try {
            Class<?> owner = Class.forName(lambda.getImplClass().replace('/', '.'), false, loader);
            if (!owner.isRecord()) return false;
            for (RecordComponent component : owner.getRecordComponents()) {
                if (component.getName().equals(method)) return true;
            }
            return false;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    static String propertyName(String method) {
        for (String prefix : new String[] {"get", "is"}) {
            int start = prefix.length();
            if (method.length() > start && method.startsWith(prefix) && Character.isUpperCase(method.charAt(start))) {
                return decapitalize(method.substring(start));
            }
        }
        return method;
    }

    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
