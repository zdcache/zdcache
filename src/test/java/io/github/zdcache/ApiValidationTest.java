package io.github.zdcache;

import io.github.zdcache.store.IdentityStoreFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiValidationTest {
    record Person(Long id, String name, List<String> tags) {}
    record Note(String text) {}

    static final IdentityDimension<Person, Long> PERSON_ID = IdentityDimension.of(Person.class, "id", Person::id);
    static final SearchSpecification<Person, Long> SPEC = SearchSpecification.builder(PERSON_ID).prefix("name").build();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putUnchecked(ZDCache cache, Object entity, SearchSpecification specification) {
        cache.put(entity, specification);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putUnchecked(ZDCache cache, Class type, Object id, Object entity, SearchSpecification specification) {
        cache.put(type, id, entity, specification);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void updateUnchecked(ZDCache cache, Class type, SearchSpecification replacement) {
        cache.updateSpecification(type, replacement);
    }

    @Test void explicitIdentityWithASpecificationIsStoredUnderTheGivenId() {
        var explicit = SearchSpecification.builder(IdentityDimension.<Note, String>explicit(Note.class))
            .prefix("text")
            .build();
        var cache = ZDCache.builder().build();

        cache.put(Note.class, "a", new Note("first"), explicit);
        cache.put(Note.class, "b", new Note("second"), explicit);

        assertEquals("first", cache.get(Note.class, "a").text());
        assertEquals(List.of("second"), cache.search(Note.class, "text", "sec").stream().map(Note::text).toList());
        assertSame(explicit, cache.getSpecification(Note.class));
    }

    @Test void explicitPutWithASpecificationRejectsMismatchedTypesAndMissingIds() {
        var cache = ZDCache.builder().build();

        assertThrows(IllegalArgumentException.class,
            () -> putUnchecked(cache, Note.class, 1L, new Person(1L, "Simon", List.of()), SPEC));
        assertThrows(NullPointerException.class, () -> cache.put(Person.class, null, new Person(1L, "Simon", List.of()), SPEC));
    }

    @Test void putRejectsAnEntityOfAnotherTypeThanItsSpecification() {
        var cache = ZDCache.builder().build();

        var thrown = assertThrows(IllegalArgumentException.class, () -> putUnchecked(cache, new Note("x"), SPEC));

        assertTrue(thrown.getMessage().contains("specification type"));
    }

    @Test void putWithAnExplicitIdentitySpecificationRequiresTheId() {
        var explicit = SearchSpecification.basic(IdentityDimension.<Note, String>explicit(Note.class));
        var cache = ZDCache.builder().build();

        var thrown = assertThrows(IllegalArgumentException.class, () -> cache.put(new Note("x"), explicit));

        assertTrue(thrown.getMessage().contains("put(Class, ID, T, specification)"));
    }

    @Test void updateSpecificationRejectsASpecificationForAnotherType() {
        var cache = ZDCache.builder().build();
        cache.register(SPEC);
        var noteSpec = SearchSpecification.basic(IdentityDimension.of(Note.class, "text", Note::text));

        assertThrows(IllegalArgumentException.class, () -> updateUnchecked(cache, Person.class, noteSpec));
    }

    @Test void updateSpecificationOfAnUnregisteredTypeFails() {
        var cache = ZDCache.builder().build();

        assertThrows(MissingIdentitySpecificationException.class, () -> cache.updateSpecification(Person.class, SPEC));
    }

    @Test void operationsOnUnknownTypesReturnNothing() {
        var cache = ZDCache.builder().build();

        assertNull(cache.remove(Person.class, 1L));
        assertNull(cache.get(Person.class, 1L));
        assertEquals(0, cache.size(Person.class));
        assertNull(cache.getSpecification(Person.class));
        assertNull(cache.searchSpecification(Person.class));
    }

    @Test void capacitiesMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> ZDCache.builder().defaultMaxPerType(0));
        assertThrows(IllegalArgumentException.class, () -> ZDCache.builder().capacity(Person.class, -1));
        for (var stores : List.of(IdentityStoreFactory.linkedHashMap(), IdentityStoreFactory.bufferedLru(),
                IdentityStoreFactory.caffeine())) {
            assertThrows(IllegalArgumentException.class, () -> stores.create(0));
        }
    }

    @Test void valueRangeBoundsMustBeOrdered() {
        assertThrows(IllegalArgumentException.class, () -> ValueRange.between(5, 1));
        assertThrows(NullPointerException.class, () -> ValueRange.between(null, 1));
        assertEquals(ValueRange.between(3, 3), new ValueRange<>(3, 3));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void redirectTargetMustBeASupertype() {
        var noteSpec = SearchSpecification.basic(IdentityDimension.of(Note.class, "text", Note::text));

        var thrown = assertThrows(IllegalArgumentException.class, () -> new TypeRedirect(Person.class, noteSpec));

        assertTrue(thrown.getMessage().contains("is not a subtype of"));
    }

    @Test void dimensionNamesMustBeNonBlankSegments() {
        var builder = SearchSpecification.builder(PERSON_ID);

        assertThrows(IllegalArgumentException.class, () -> builder.prefix(""));
        assertThrows(IllegalArgumentException.class, () -> builder.prefix("first.name"));
        assertThrows(IllegalArgumentException.class, () -> builder.prefix(" ", Person::name));
        assertThrows(IllegalArgumentException.class, () -> builder.exact((String) null, Person::name));
        assertThrows(IllegalArgumentException.class,
            () -> builder.each("tags", Person::tags, tag -> tag.exact("a.b", t -> t)));
        assertThrows(IllegalArgumentException.class,
            () -> builder.compound("pair", c -> c.exact("a.b", Person::name).prefix("name", Person::name)));
        assertThrows(IllegalArgumentException.class,
            () -> builder.compound("pair", c -> c.exact("", Person::name).prefix("name", Person::name)));
    }

    @Test void identityDimensionsKeepTheirName() {
        assertEquals("id", PERSON_ID.name());
        assertEquals("$explicit", IdentityDimension.explicit(Note.class).name());
    }

    @Test void rangeDimensionsRejectValuesThatAreNotComparable() {
        var spec = SearchSpecification.builder(PERSON_ID).range("tags").build();
        var cache = ZDCache.builder().build();

        var thrown = assertThrows(IllegalArgumentException.class, () -> cache.put(new Person(1L, "Simon", List.of("x")), spec));

        assertTrue(thrown.getMessage().contains("RANGE value must be Comparable"));
    }

    @Test void searchRequiresTheDeclaredNumberOfValues() {
        var spec = SearchSpecification.builder(PERSON_ID)
            .prefix("name")
            .compound("nameTag", c -> c.exact("name").exactEach("tags"))
            .build();
        var cache = ZDCache.builder().build();
        cache.register(spec);

        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "name", (Object[]) null));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "name", "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "nameTag", (Object[]) null));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "nameTag", "Simon"));
    }

    @Test void explicitPutWithASpecificationChecksTheEntityTypeAndTheExtractedId() {
        var cache = ZDCache.builder().build();
        cache.register(SPEC);

        assertThrows(IllegalArgumentException.class,
            () -> putUnchecked(cache, Person.class, 1L, new Note("x"), SPEC));
        var thrown = assertThrows(IllegalArgumentException.class,
            () -> cache.put(Person.class, 2L, new Person(1L, "Simon", List.of()), SPEC));
        assertTrue(thrown.getMessage().contains("differs"));
    }

    @Test void compoundPartNamesMustBeNonBlankSegments() {
        var builder = SearchSpecification.builder(PERSON_ID);

        assertThrows(IllegalArgumentException.class, () -> builder.compound("pair", c -> c.exact("").prefix("name")));
        assertThrows(IllegalArgumentException.class, () -> builder.compound("pair", c -> c.exact("a.b").prefix("name")));
        assertThrows(IllegalArgumentException.class,
            () -> builder.compound("pair", c -> c.exact((String) null, Person::name).prefix("name")));
    }
}
