package io.github.zdcache;

import io.github.zdcache.store.IdentityStoreFactory;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompoundSearchTest {
    record Address(String country, String city) {}
    record Person(Long id, String name, String city, int age, List<String> tags, List<Address> addresses) {}

    static final IdentityDimension<Person, Long> PERSON_ID =
        IdentityDimension.of(Person.class, "id", Person::id);

    private static Person person(long id, String name, String city, int age, List<String> tags, Address... addresses) {
        return new Person(id, name, city, age, tags, List.of(addresses));
    }

    private static SearchSpecification<Person, Long> spec() {
        return SearchSpecification.builder(PERSON_ID)
            .prefix("name", Person::name)
            .compound("cityAge", c -> c.exact("city", Person::city).range("age", Person::age))
            .compound("cityName", c -> c.exact("city", Person::city).prefix("name", Person::name))
            .compound("cityTag", c -> c.exact("city", Person::city).exactEach("tag", Person::tags))
            .each("addresses", Person::addresses, a -> a
                .compound("place", c -> c.exact("country", Address::country).exact("city", Address::city)))
            .build();
    }

    private static List<Long> ids(List<Person> people) {
        return people.stream().map(Person::id).sorted().toList();
    }

    private static void forEachStore(int size, Consumer<ZDCache> scenario) {
        scenario.accept(ZDCache.builder().defaultMaxPerType(size).identityStore(IdentityStoreFactory.linkedHashMap()).build());
        scenario.accept(ZDCache.builder().defaultMaxPerType(size).identityStore(IdentityStoreFactory.caffeine()).build());
        scenario.accept(ZDCache.builder().defaultMaxPerType(size).identityStore(IdentityStoreFactory.bufferedLru()).build());
    }

    private static ZDCache populated(SearchSpecification<Person, Long> spec) {
        var cache = ZDCache.builder().build();
        cache.put(person(1, "Simon", "Oslo", 29, List.of("home", "work")), spec);
        cache.put(person(2, "Siri", "Oslo", 41, List.of("home")));
        cache.put(person(3, "Sven", "Bergen", 25, List.of("work")));
        cache.put(person(4, "Petter", "Oslo", 25, List.of()));
        return cache;
    }

    @Test void exactPartsWithRangeLastSearchOneSlice() {
        var cache = populated(spec());

        assertEquals(List.of(1L, 4L), ids(cache.search(Person.class, "cityAge", "Oslo", ValueRange.between(20, 30))));
        assertEquals(List.of(4L), ids(cache.search(Person.class, "cityAge", "Oslo", 25)));
        assertEquals(List.of(3L), ids(cache.search(Person.class, "cityAge", "Bergen", ValueRange.between(0, 100))));
        assertTrue(cache.search(Person.class, "cityAge", "Trondheim", ValueRange.between(0, 100)).isEmpty());
    }

    @Test void exactPartsWithPrefixLast() {
        var cache = populated(spec());

        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, "cityName", "Oslo", "SI")));
        assertEquals(List.of(3L), ids(cache.search(Person.class, "cityName", "Bergen", "s")));
    }

    @Test void multiValuedPartsIndexEveryCombination() {
        var cache = populated(spec());

        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, "cityTag", "Oslo", "home")));
        assertEquals(List.of(1L), ids(cache.search(Person.class, "cityTag", "Oslo", "work")));
        assertEquals(List.of(3L), ids(cache.search(Person.class, "cityTag", "Bergen", "work")));
    }

    @Test void compoundInsideEachMatchesWithinTheSameElement() {
        var spec = spec();
        var cache = ZDCache.builder().build();
        cache.put(person(1, "Simon", "Oslo", 29, List.of(),
            new Address("Norway", "Bergen"), new Address("Sweden", "Oslo")), spec);
        cache.put(person(2, "Siri", "Oslo", 41, List.of(), new Address("Norway", "Oslo")));

        assertEquals(List.of(2L), ids(cache.search(Person.class, "addresses.place", "Norway", "Oslo")));
        assertEquals(List.of(1L), ids(cache.search(Person.class, "addresses.place", "Sweden", "Oslo")));
    }

    @Test void replacementRemovalAndEvictionKeepCompoundIndexesConsistent() {
        forEachStore(2, cache -> {
            var spec = spec();
            cache.put(person(1, "Simon", "Oslo", 29, List.of()), spec);
            cache.put(person(1, "Simon", "Bergen", 29, List.of()));
            assertTrue(cache.search(Person.class, "cityAge", "Oslo", 29).isEmpty());
            assertEquals(List.of(1L), ids(cache.search(Person.class, "cityAge", "Bergen", 29)));

            cache.remove(Person.class, 1L);
            assertTrue(cache.search(Person.class, "cityAge", "Bergen", 29).isEmpty());

            for (long id = 10; id < 20; id++) cache.put(person(id, "P" + id, "Oslo", 30, List.of()));
            assertEquals(cache.size(Person.class), cache.search(Person.class, "cityAge", "Oslo", 30).size());
        });
    }

    @Test void updateSpecificationBuildsNewCompoundIndexes() {
        var basic = SearchSpecification.builder(PERSON_ID).prefix("name", Person::name).build();
        var cache = populated(basic);
        var extended = SearchSpecification.builder(PERSON_ID)
            .prefix("name", Person::name)
            .compound("cityAge", c -> c.exact("city", Person::city).range("age", Person::age))
            .build();

        cache.updateSpecification(Person.class, extended);

        assertEquals(List.of(2L), ids(cache.search(Person.class, "cityAge", "Oslo", ValueRange.between(40, 50))));
    }

    @Test void specificationDescribesWhatCanBeSearched() {
        var cache = populated(spec());
        var found = cache.getSpecification(Person.class);

        assertEquals(List.of("name", "cityAge", "cityName", "cityTag", "addresses.place"),
            found.dimensions().stream().map(SearchDimension::path).toList());
        assertEquals(IndexKind.COMPOUND, found.dimension("cityAge").kind());
        assertEquals(List.of(new SearchDimension.Part("city", IndexKind.EXACT), new SearchDimension.Part("age", IndexKind.RANGE)),
            found.dimension("cityAge").parts());
        assertEquals(List.of(), found.dimension("name").parts());
        assertThrows(UnknownSearchDimensionException.class, () -> found.dimension("city"));
    }

    static class Animal { final long id; final String kind; final int age;
        Animal(long id, String kind, int age) { this.id = id; this.kind = kind; this.age = age; } }
    static class Dog extends Animal { Dog(long id, int age) { super(id, "dog", age); } }

    @Test void searchSpecificationResolvesThroughSubtypes() {
        var animalSpec = SearchSpecification.builder(IdentityDimension.of(Animal.class, "id", (Animal a) -> a.id))
            .compound("kindAge", c -> c.exact("kind").range("age"))
            .build();
        var cache = ZDCache.builder().build();
        cache.register(animalSpec);
        cache.put(new Dog(1, 3));
        cache.put(new Animal(2, "dog", 4));

        var resolved = cache.searchSpecification(Dog.class);
        assertSame(animalSpec, resolved);
        assertEquals(IndexKind.COMPOUND, resolved.dimension("kindAge").kind());
        var dogs = cache.search(Dog.class, "kindAge", "dog", ValueRange.between(0, 10));
        assertEquals(List.of(1L), dogs.stream().map(d -> d.id).toList());
        assertNull(cache.getSpecification(Dog.class));
        assertNull(cache.searchSpecification(String.class));
    }

    @Test void searchValuesAreValidatedAgainstTheDeclaredParts() {
        var cache = populated(spec());

        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "cityAge", "Oslo"));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "cityAge", "Oslo", 20, 30));
        assertThrows(IllegalArgumentException.class,
            () -> cache.search(Person.class, "cityAge", ValueRange.between("A", "B"), 20));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "cityAge", "Oslo", null));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "cityAge", "Oslo", new Object()));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "cityName", "Oslo", 42));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "name", "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "name"));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Person.class, "name", 42));
    }

    @Test void rangeQueriesOfTheWrongTypeFailClearly() {
        var cache = populated(spec());

        var thrown = assertThrows(IllegalArgumentException.class,
            () -> cache.search(Person.class, "cityAge", "Oslo", ValueRange.between(20L, 30L)));
        assertInstanceOf(ClassCastException.class, thrown.getCause());
    }

    @Test void compoundDeclarationsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> SearchSpecification.builder(PERSON_ID)
            .compound("single", c -> c.exact("city", Person::city)));
        assertThrows(IllegalArgumentException.class, () -> SearchSpecification.builder(PERSON_ID)
            .compound("rangeFirst", c -> c.range("age", Person::age).exact("city", Person::city)));
        assertThrows(IllegalArgumentException.class, () -> SearchSpecification.builder(PERSON_ID)
            .compound("prefixFirst", c -> c.prefix("name", Person::name).exact("city", Person::city)));
        assertThrows(IllegalArgumentException.class, () -> SearchSpecification.builder(PERSON_ID)
            .compound("twice", c -> c.exact("city", Person::city).exact("city", Person::name)));
        assertThrows(IllegalArgumentException.class, () -> SearchSpecification.builder(PERSON_ID)
            .compound("dotted", c -> c.exact("a.b", Person::city).exact("c", Person::name)));
        assertThrows(IllegalArgumentException.class, () -> SearchSpecification.builder(PERSON_ID)
            .prefix("name", Person::name)
            .compound("name", c -> c.exact("city", Person::city).exact("n", Person::name)));
    }

    @Test void entitiesMissingAPartValueAreNotIndexedInThatCompound() {
        var spec = spec();
        var cache = ZDCache.builder().build();
        cache.put(person(1, "Simon", null, 29, List.of()), spec);
        cache.put(person(2, "Siri", "Oslo", 41, List.of()));

        assertEquals(List.of(2L), ids(cache.search(Person.class, "cityAge", "Oslo", ValueRange.between(0, 100))));
        assertTrue(cache.search(Person.class, "cityTag", "Oslo", "home").isEmpty());
    }
}
