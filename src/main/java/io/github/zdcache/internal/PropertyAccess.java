package io.github.zdcache.internal;

import io.github.zdcache.PropertyAccessException;
import io.github.zdcache.UnknownPropertyException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class PropertyAccess {
    private static final ClassValue<Map<String, Function<Object, Object>>> READERS = new ClassValue<>() {
        @Override protected Map<String, Function<Object, Object>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private PropertyAccess() { }

    public static Function<Object, Object> reader(Class<?> type, String property) {
        Map<String, Function<Object, Object>> readers = READERS.get(type);
        Function<Object, Object> reader = readers.get(property);
        return reader != null ? reader : readers.computeIfAbsent(property, name -> resolve(type, name));
    }

    public static Object read(Object target, String property) {
        return reader(target.getClass(), property).apply(target);
    }

    public static Iterable<?> readAll(Object target, String property) {
        Object value = read(target, property);
        if (value == null) return List.of();
        if (value instanceof Iterable<?> values) return values;
        return List.of(value);
    }

    private static Function<Object, Object> resolve(Class<?> type, String property) {
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (component.getName().equals(property)) return invoking(type, property, component.getAccessor());
            }
        }
        String capitalized = property.substring(0, 1).toUpperCase(Locale.ROOT) + property.substring(1);
        Method method = accessor(type, property);
        if (method == null) method = accessor(type, "get" + capitalized);
        if (method == null) {
            Method is = accessor(type, "is" + capitalized);
            if (is != null && (is.getReturnType() == boolean.class || is.getReturnType() == Boolean.class)) method = is;
        }
        if (method != null) return invoking(type, property, method);
        Field field = field(type, property);
        if (field != null) return reading(type, property, field);
        throw new UnknownPropertyException(type, property);
    }

    private static Method accessor(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class) return null;
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                if (!Modifier.isStatic(field.getModifiers())) return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Function<Object, Object> invoking(Class<?> type, String property, Method method) {
        method.trySetAccessible();
        return target -> {
            try {
                return method.invoke(target);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                if (e.getCause() instanceof Error error) throw error;
                throw new PropertyAccessException(type, property, e.getCause());
            } catch (IllegalAccessException e) {
                throw new PropertyAccessException(type, property, e);
            }
        };
    }

    private static Function<Object, Object> reading(Class<?> type, String property, Field field) {
        field.trySetAccessible();
        return target -> {
            try {
                return field.get(target);
            } catch (IllegalAccessException e) {
                throw new PropertyAccessException(type, property, e);
            }
        };
    }
}
