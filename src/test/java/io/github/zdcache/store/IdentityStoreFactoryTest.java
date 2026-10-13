package io.github.zdcache.store;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityStoreFactoryTest {

    @Test void caffeineCreatesCaffeineStoreWhenCaffeineIsOnClasspath() {
        assertInstanceOf(IdentityStoreCaffeine.class, IdentityStoreFactory.caffeine().create(10));
    }

    @Test void caffeineFailsFastWithClearMessageWhenCaffeineIsMissing() throws Exception {
        try (var withoutCaffeine = zdcacheOnlyClassLoader()) {
            var factory = withoutCaffeine.loadClass(IdentityStoreFactory.class.getName());

            var thrown = assertThrows(InvocationTargetException.class,
                () -> factory.getMethod("caffeine").invoke(null));

            var cause = assertInstanceOf(IllegalStateException.class, thrown.getCause());
            assertTrue(cause.getMessage().contains("com.github.ben-manes.caffeine:caffeine"));
        }
    }

    @Test void linkedHashMapWorksWhenCaffeineIsMissing() throws Exception {
        try (var withoutCaffeine = zdcacheOnlyClassLoader()) {
            var factory = withoutCaffeine.loadClass(IdentityStoreFactory.class.getName());

            Object linkedHashMap = factory.getMethod("linkedHashMap").invoke(null);
            Object store = factory.getMethod("create", int.class).invoke(linkedHashMap, 10);

            assertEquals(IdentityStoreLinkedHashMap.class.getName(), store.getClass().getName());
        }
    }

    @Test void bufferedLruWorksWhenCaffeineIsMissing() throws Exception {
        try (var withoutCaffeine = zdcacheOnlyClassLoader()) {
            var factory = withoutCaffeine.loadClass(IdentityStoreFactory.class.getName());

            Object bufferedLru = factory.getMethod("bufferedLru").invoke(null);
            Object store = factory.getMethod("create", int.class).invoke(bufferedLru, 10);

            assertEquals(IdentityStoreBufferedLru.class.getName(), store.getClass().getName());
        }
    }

    private static URLClassLoader zdcacheOnlyClassLoader() {
        URL mainClasses = IdentityStoreFactory.class.getProtectionDomain().getCodeSource().getLocation();
        return new URLClassLoader(new URL[] {mainClasses}, ClassLoader.getPlatformClassLoader());
    }
}
