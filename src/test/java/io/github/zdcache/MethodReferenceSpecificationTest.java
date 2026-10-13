package io.github.zdcache;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MethodReferenceSpecificationTest {
    record Address(String country, String city) {
        List<String> tagsOf() { return List.of(country, city); }
    }
    record Person(Long id, String name, String city, int age, List<String> tags, List<Address> addresses) {}
    record Book(long id, String isbn, String getaway, boolean isActive) {
        String getTitleCase() { return isbn.toUpperCase(); }
    }

    static class Customer {
        private final long id;
        private final String name;
        private final boolean active;
        private final String url;

        Customer(long id, String name, boolean active, String url) {
            this.id = id; this.name = name; this.active = active; this.url = url;
        }
        public long getId() { return id; }
        public String getName() { return name; }
        public boolean isActive() { return active; }
        public String getURL() { return url; }
    }

    static class VipCustomer extends Customer {
        VipCustomer(long id, String name) { super(id, name, true, "vip"); }
    }

    interface Named {
        long id();
        String name();
    }
    record Pet(long id, String name, String species) implements Named {}
    record Note(String text) {}

    static final IdentityDimension<Person, Long> PERSON_ID = IdentityDimension.of(Person.class, "id", Person::id);
    static final IdentityDimension<Customer, Long> CUSTOMER_ID = IdentityDimension.of(Customer.class, "id", Customer::getId);

    private static List<String> paths(SearchSpecification<?, ?> spec) {
        return spec.dimensions().stream().map(SearchDimension::path).toList();
    }

    private static List<Long> personIds(List<Person> people) {
        return people.stream().map(Person::id).sorted().toList();
    }

    private static ZDCache people(SearchSpecification<Person, Long> spec) {
        var cache = ZDCache.builder().build();
        cache.put(new Person(1L, "Simon", "Oslo", 29, List.of("home", "work"), List.of(new Address("Norway", "Bergen"))), spec);
        cache.put(new Person(2L, "Siri", "Bergen", 41, List.of("home"), List.of(new Address("Sweden", "Oslo"))));
        cache.put(new Person(3L, "Petter", "Oslo", 25, List.of(), List.of()));
        return cache;
    }

    @Test void recordComponentsNameTheirDimensions() {
        var spec = SearchSpecification.builder(PERSON_ID)
            .prefix(Person::name)
            .exact(Person::city)
            .range(Person::age)
            .exactEach(Person::tags)
            .build();

        assertEquals(List.of("name", "city", "age", "tags"), paths(spec));
    }

    @Test void duplicateDerivedNamesAreRejectedLikeExplicitOnes() {
        var builder = SearchSpecification.builder(PERSON_ID).exactEach(Person::tags);

        assertThrows(IllegalArgumentException.class, () -> builder.prefixEach(Person::tags));
    }

    @Test void searchAcceptsTheSameMethodReference() {
        var cache = people(SearchSpecification.builder(PERSON_ID)
            .prefix(Person::name)
            .exact(Person::city)
            .range(Person::age)
            .build());

        assertEquals(List.of(1L, 2L), personIds(cache.search(Person.class, Person::name, "si")));
        assertEquals(List.of(1L, 3L), personIds(cache.search(Person.class, Person::city, "Oslo")));
        assertEquals(List.of(1L, 3L), personIds(cache.search(Person.class, Person::age, ValueRange.between(20, 30))));
        assertEquals(personIds(cache.search(Person.class, "name", "si")),
            personIds(cache.search(Person.class, Person::name, "si")));
    }

    @Test void nestedAndCompoundDimensionsUseMethodReferenceNames() {
        var spec = SearchSpecification.builder(PERSON_ID)
            .each(Person::addresses, address -> address
                .exact(Address::city)
                .compound("place", c -> c.exact(Address::country).exact(Address::city)))
            .compound("cityAge", c -> c.exact(Person::city).range(Person::age))
            .build();
        var cache = people(spec);

        assertEquals(List.of("addresses.city", "addresses.place", "cityAge"), paths(spec));
        assertEquals(List.of("country", "city"),
            spec.dimension("addresses.place").parts().stream().map(SearchDimension.Part::name).toList());
        assertEquals(List.of(2L), personIds(cache.search(Person.class, "addresses.place", "Sweden", "Oslo")));
        assertEquals(List.of(3L), personIds(cache.search(Person.class, "cityAge", "Oslo", 25)));
    }

    @Test void recordMethodsThatAreNotComponentsAreNamedLikeBeanGetters() {
        var spec = SearchSpecification.builder(IdentityDimension.of(Book.class, "id", Book::id))
            .exact(Book::getTitleCase)
            .build();

        assertEquals(List.of("titleCase"), paths(spec));
    }

    @Test void collectionsInsideEachAreNamedByTheirMethodReference() {
        var spec = SearchSpecification.builder(PERSON_ID)
            .each(Person::addresses, address -> address.exactEach(Address::tagsOf))
            .build();

        assertEquals(List.of("addresses.tagsOf"), paths(spec));
    }

    @Test void beanGettersDropTheirGetAndIsPrefix() {
        var spec = SearchSpecification.builder(CUSTOMER_ID)
            .prefix(Customer::getName)
            .exact(Customer::isActive)
            .exact(Customer::getURL)
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Customer(1, "Simon", true, "a"), spec);
        cache.put(new Customer(2, "Siri", false, "b"));

        assertEquals(List.of("name", "active", "URL"), paths(spec));
        assertEquals(1, cache.search(Customer.class, Customer::isActive, true).size());
        assertEquals(2, cache.search(Customer.class, "name", "si").size());
    }

    @Test void recordComponentsKeepTheirNameEvenWhenTheyLookLikeGetters() {
        var spec = SearchSpecification.builder(IdentityDimension.of(Book.class, "id", Book::id))
            .exact(Book::isbn)
            .exact(Book::getaway)
            .exact(Book::isActive)
            .build();

        assertEquals(List.of("isbn", "getaway", "isActive"), paths(spec));
    }

    @Test void searchThroughASubtypeAcceptsAMethodReferenceOnTheSupertype() {
        var spec = SearchSpecification.builder(CUSTOMER_ID).prefix(Customer::getName).build();
        var cache = ZDCache.builder().build();
        cache.put(new Customer(1, "Simon", false, "a"), spec);
        cache.put(new VipCustomer(2, "Siri"));

        List<VipCustomer> found = cache.search(VipCustomer.class, Customer::getName, "si");

        assertEquals(List.of(2L), found.stream().map(Customer::getId).toList());
    }

    @Test void lambdasNeedAnExplicitName() {
        var builder = SearchSpecification.builder(PERSON_ID);

        var thrown = assertThrows(IllegalArgumentException.class, () -> builder.prefix(p -> p.name().trim()));

        assertTrue(thrown.getMessage().contains("pass the name explicitly"));
        assertDoesNotThrow(() -> builder.prefix("trimmedName", p -> p.name().trim()));
    }

    @Test void searchingByAMethodReferenceThatIsNotADimensionFails() {
        var cache = people(SearchSpecification.builder(PERSON_ID).prefix(Person::name).build());

        assertThrows(UnknownSearchDimensionException.class, () -> cache.search(Person.class, Person::city, "Oslo"));
    }

    @Test void everySearchFormFindsTheSameEntities() {
        var cache = people(SearchSpecification.builder(PERSON_ID).prefix(Person::name).build());

        var byName = personIds(cache.search(Person.class, "name", "si"));

        assertEquals(List.of(1L, 2L), byName);
        assertEquals(byName, personIds(cache.search(Person.class, Person::name, "si")));
        assertEquals(byName, personIds(cache.search(Person::name, "si")));
    }

    @Test void searchByAMethodReferenceAloneInfersTheType() {
        var cache = people(SearchSpecification.builder(PERSON_ID)
            .prefix(Person::name)
            .exact(Person::city)
            .range(Person::age)
            .build());

        List<Person> inOslo = cache.search(Person::city, "Oslo");

        assertEquals(List.of(1L, 3L), personIds(inOslo));
        assertEquals(List.of(1L, 3L), personIds(cache.search(Person::age, ValueRange.between(20, 30))));
    }

    @Test void anInheritedGetterWrittenOnTheSubtypeSearchesOnlyThatSubtype() {
        var spec = SearchSpecification.builder(CUSTOMER_ID).prefix(Customer::getName).build();
        var cache = ZDCache.builder().build();
        cache.put(new Customer(1, "Simon", false, "a"), spec);
        cache.put(new VipCustomer(2, "Siri"));

        List<VipCustomer> vips = cache.search(VipCustomer::getName, "si");
        List<Customer> everyone = cache.search(Customer::getName, "si");

        assertEquals(List.of(2L), vips.stream().map(Customer::getId).toList());
        assertEquals(List.of(1L, 2L), everyone.stream().map(Customer::getId).sorted().toList());
    }

    @Test void interfaceMethodReferencesResolveThroughTheInterfaceSpecification() {
        var spec = SearchSpecification.builder(IdentityDimension.of(Named.class, "id", Named::id))
            .prefix(Named::name)
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Pet(1, "Rex", "dog"), spec);
        cache.put(new Pet(2, "Rita", "cat"));
        cache.put(new Pet(3, "Bella", "dog"));

        List<Named> named = cache.search(Named::name, "r");
        List<Pet> pets = cache.search(Pet::name, "r");

        assertEquals(List.of(1L, 2L), named.stream().map(Named::id).sorted().toList());
        assertEquals(List.of(1L, 2L), pets.stream().map(Pet::id).sorted().toList());
    }

    @Test void searchingAnUnregisteredTypeByMethodReferenceFails() {
        var cache = ZDCache.builder().build();

        assertThrows(MissingSearchSpecificationStrategyException.class, () -> cache.search(Note::text, "x"));
    }

    @Test void searchingByALambdaFailsWithAClearMessage() {
        var cache = people(SearchSpecification.builder(PERSON_ID).prefix(Person::name).build());

        var thrown = assertThrows(IllegalArgumentException.class,
            () -> cache.search((Person p) -> p.name(), "si"));

        assertTrue(thrown.getMessage().contains("method reference"));
    }

    @Test void compoundPartsAcceptCollectionMethodReferences() {
        var spec = SearchSpecification.builder(PERSON_ID)
            .compound("cityTag", c -> c.exactEach(Person::tags).prefix(Person::name))
            .build();
        var cache = people(spec);

        assertEquals(List.of("tags", "name"),
            spec.dimension("cityTag").parts().stream().map(SearchDimension.Part::name).toList());
        assertEquals(List.of(1L, 2L), personIds(cache.search(Person.class, "cityTag", "home", "s")));
    }
}
