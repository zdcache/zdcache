package io.github.zdcache.internal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GettersTest {

    @Test void beanPrefixesAreDroppedOnlyBeforeAnUppercaseLetter() {
        assertEquals("name", Getters.propertyName("getName"));
        assertEquals("active", Getters.propertyName("isActive"));
        assertEquals("x", Getters.propertyName("getX"));
        assertEquals("URL", Getters.propertyName("getURL"));
        assertEquals("getaway", Getters.propertyName("getaway"));
        assertEquals("island", Getters.propertyName("island"));
    }

    @Test void barePrefixesAreKept() {
        assertEquals("get", Getters.propertyName("get"));
        assertEquals("is", Getters.propertyName("is"));
    }
}
