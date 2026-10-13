package io.github.zdcache;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReflectiveSpecificationTest {
    record Address(String country, String city) {}
    record Person(Long id, String name, String city, int age, List<String> tags, List<Address> addresses) {}

    static class Customer {
        private final long id;
        private final String name;
        private final boolean active;
        public final String segment;
        private final String hidden;
        private final Address address;

        Customer(long id, String name, boolean active, String segment, String hidden, Address address) {
            this.id = id; this.name = name; this.active = active;
            this.segment = segment; this.hidden = hidden; this.address = address;
        }
        public long getId() { return id; }
        public String getName() { return name; }
        public boolean isActive() { return active; }
    }

    static class VipCustomer extends Customer {
        VipCustomer(long id, String name) { super(id, name, true, "vip", "secret", null); }
        @Override public String getName() { return "VIP " + super.getName(); }
    }

    static class CheckedFailure {
        final long id = 1;
        public String getName() throws java.io.IOException { throw new java.io.IOException("disk"); }
    }

    static class BrokenInvariant {
        final long id = 1;
        public String getName() { throw new AssertionError("broken"); }
    }

    static class Gadget {
        final long id;
        private final String code;
        private final String label;
        private final String model;

        Gadget(long id, String code, String label, String model) {
            this.id = id; this.code = code; this.label = label; this.model = model;
        }
        public String code() { return "plain-" + code; }
        public static String label() { return "static"; }
        public String isLabel() { return "not a boolean"; }
        public void model() { }
        public Boolean isArchived() { return Boolean.TRUE; }
    }

    static class Item {
        final long id;
        private final String sku;
        Item(long id, String sku) { this.id = id; this.sku = sku; }
    }

    static class ShadowingItem extends Item {
        static final String sku = "static";
        ShadowingItem(long id, String sku) { super(id, sku); }
    }

    static class Exploding {
        final long id = 1;
        public String getName() { throw new IllegalStateException("boom"); }
    }

    static final IdentityDimension<Person, Long> PERSON_ID =
        IdentityDimension.of(Person.class, "id", Person::id);
    static final IdentityDimension<Customer, Long> CUSTOMER_ID =
        IdentityDimension.of(Customer.class, "id", Customer::getId);

    private static List<Long> personIds(List<Person> people) {
        return people.stream().map(Person::id).sorted().toList();
    }

    private static List<Long> customerIds(List<? extends Customer> customers) {
        return customers.stream().map(Customer::getId).sorted().toList();
    }

    @Test void recordComponentsAreReadByName() {
        var spec = SearchSpecification.builder(PERSON_ID)
            .prefix("name")
            .exact("city")
            .range("age")
            .exactEach("tags")
            .each("addresses", a -> a.exact("country"))
            .compound("cityAge", c -> c.exact("city").range("age"))
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Person(1L, "Simon", "Oslo", 29, List.of("home"), List.of(new Address("Norway", "Oslo"))), spec);
        cache.put(new Person(2L, "Siri", "Bergen", 41, List.of("work"), List.of(new Address("Sweden", "Lund"))));

        assertEquals(List.of(1L, 2L), personIds(cache.search(Person.class, "name", "si")));
        assertEquals(List.of(2L), personIds(cache.search(Person.class, "city", "Bergen")));
        assertEquals(List.of(1L), personIds(cache.search(Person.class, "age", ValueRange.between(20, 30))));
        assertEquals(List.of(2L), personIds(cache.search(Person.class, "tags", "work")));
        assertEquals(List.of(1L), personIds(cache.search(Person.class, "addresses.country", "Norway")));
        assertEquals(List.of(1L), personIds(cache.search(Person.class, "cityAge", "Oslo", 29)));
    }

    @Test void classesAreReadThroughGettersPublicFieldsAndPrivateFields() {
        var spec = SearchSpecification.builder(CUSTOMER_ID)
            .prefix("name")
            .exact("active")
            .exact("segment")
            .exact("hidden")
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Customer(1, "Simon", true, "retail", "a", null), spec);
        cache.put(new Customer(2, "Siri", false, "corporate", "b", null));

        assertEquals(List.of(1L, 2L), customerIds(cache.search(Customer.class, "name", "s")));
        assertEquals(List.of(1L), customerIds(cache.search(Customer.class, "active", true)));
        assertEquals(List.of(2L), customerIds(cache.search(Customer.class, "segment", "corporate")));
        assertEquals(List.of(2L), customerIds(cache.search(Customer.class, "hidden", "b")));
    }

    @Test void propertiesAreReadFromTheRuntimeClassOfSubtypes() {
        var spec = SearchSpecification.builder(CUSTOMER_ID).prefix("name").exact("segment").build();
        var cache = ZDCache.builder().build();
        cache.register(spec);
        cache.put(new VipCustomer(7, "Vera"));

        assertEquals(List.of(7L), customerIds(cache.search(Customer.class, "name", "vip v")));
        assertEquals(List.of(7L), customerIds(cache.search(VipCustomer.class, "segment", "vip")));
    }

    @Test void singleNestedObjectsAreTraversedLikeOneElementCollections() {
        var spec = SearchSpecification.builder(CUSTOMER_ID)
            .each("address", a -> a.exact("city"))
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Customer(1, "Simon", true, "retail", "a", new Address("Norway", "Oslo")), spec);
        cache.put(new Customer(2, "Siri", true, "retail", "b", null));

        assertEquals(List.of(1L), customerIds(cache.search(Customer.class, "address.city", "Oslo")));
    }

    @Test void reflectionAndExtractorsCanBeMixed() {
        var spec = SearchSpecification.builder(PERSON_ID)
            .prefix("name")
            .exact("upperCity", p -> p.city().toUpperCase())
            .compound("cityDecade", c -> c.exact("city").range("decade", p -> p.age() / 10))
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Person(1L, "Simon", "Oslo", 29, List.of(), List.of()), spec);

        assertEquals(List.of(1L), personIds(cache.search(Person.class, "upperCity", "OSLO")));
        assertEquals(List.of(1L), personIds(cache.search(Person.class, "cityDecade", "Oslo", 2)));
    }

    @Test void unknownRootPropertiesFailWhenTheSpecificationIsBuilt() {
        assertThrows(UnknownPropertyException.class,
            () -> SearchSpecification.builder(PERSON_ID).prefix("nmae"));
        assertThrows(UnknownPropertyException.class,
            () -> SearchSpecification.builder(PERSON_ID).compound("cityAge", c -> c.exact("cty").range("age")));
    }

    @Test void unknownNestedPropertiesFailOnInsertWithoutChangingTheCache() {
        var spec = SearchSpecification.builder(PERSON_ID)
            .prefix("name")
            .each("addresses", a -> a.exact("zip"))
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Person(1L, "Simon", "Oslo", 29, List.of(), List.of()), spec);

        assertThrows(UnknownPropertyException.class, () -> cache.put(
            new Person(2L, "Siri", "Oslo", 41, List.of(), List.of(new Address("Norway", "Oslo")))));
        assertNull(cache.get(Person.class, 2L));
        assertEquals(List.of(1L), personIds(cache.search(Person.class, "name", "s")));
    }

    @Test void wrongPropertyTypesAreReportedByTheDimension() {
        var spec = SearchSpecification.builder(PERSON_ID).prefix("age").build();
        var cache = ZDCache.builder().build();

        var thrown = assertThrows(IllegalArgumentException.class,
            () -> cache.put(new Person(1L, "Simon", "Oslo", 29, List.of(), List.of()), spec));
        assertTrue(thrown.getMessage().contains("PREFIX value must be a String"));
    }

    @Test void exceptionsFromGettersPropagate() {
        var spec = SearchSpecification.builder(IdentityDimension.of(Exploding.class, "id", (Exploding e) -> e.id))
            .prefix("name")
            .build();
        var cache = ZDCache.builder().build();

        var thrown = assertThrows(IllegalStateException.class, () -> cache.put(new Exploding(), spec));
        assertEquals("boom", thrown.getMessage());
    }

    @Test void checkedExceptionsFromGettersAreWrapped() {
        var spec = SearchSpecification.builder(IdentityDimension.of(CheckedFailure.class, "id", (CheckedFailure c) -> c.id))
            .prefix("name")
            .build();
        var cache = ZDCache.builder().build();

        var thrown = assertThrows(PropertyAccessException.class, () -> cache.put(new CheckedFailure(), spec));

        assertInstanceOf(java.io.IOException.class, thrown.getCause());
        assertTrue(thrown.getMessage().contains("'name'"));
    }

    @Test void errorsFromGettersPropagateUnwrapped() {
        var spec = SearchSpecification.builder(IdentityDimension.of(BrokenInvariant.class, "id", (BrokenInvariant b) -> b.id))
            .prefix("name")
            .build();
        var cache = ZDCache.builder().build();

        var thrown = assertThrows(AssertionError.class, () -> cache.put(new BrokenInvariant(), spec));

        assertEquals("broken", thrown.getMessage());
    }

    @Test void plainNamedAccessorsWinAndStaticVoidOrNonBooleanMethodsAreSkipped() {
        var spec = SearchSpecification.builder(IdentityDimension.of(Gadget.class, "id", (Gadget g) -> g.id))
            .exact("code")
            .exact("label")
            .exact("model")
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Gadget(1, "x1", "red", "mk2"), spec);

        assertEquals(1, cache.search(Gadget.class, "code", "plain-x1").size());
        assertEquals(1, cache.search(Gadget.class, "label", "red").size());
        assertEquals(1, cache.search(Gadget.class, "model", "mk2").size());
    }

    @Test void staticFieldsAreSkippedInFavourOfInheritedInstanceFields() {
        var spec = SearchSpecification.builder(IdentityDimension.of(ShadowingItem.class, "id", (ShadowingItem i) -> i.id))
            .exact("sku")
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new ShadowingItem(1, "abc"), spec);

        assertEquals(1, cache.search(ShadowingItem.class, "sku", "abc").size());
        assertTrue(cache.search(ShadowingItem.class, "sku", "static").isEmpty());
    }

    @Test void boxedBooleanIsGettersAreRead() {
        var spec = SearchSpecification.builder(IdentityDimension.of(Gadget.class, "id", (Gadget g) -> g.id))
            .exact("archived")
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Gadget(1, "x1", "red", "mk2"), spec);

        assertEquals(1, cache.search(Gadget.class, "archived", true).size());
    }

    @Test void compoundsInsideEachReadPropertiesOfTheElements() {
        var spec = SearchSpecification.builder(PERSON_ID)
            .each("addresses", address -> address.compound("place", c -> c.exact("country").exact("city")))
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Person(1L, "Simon", "Oslo", 29, List.of(), List.of(new Address("Norway", "Bergen"))), spec);

        assertEquals(List.of(1L), personIds(cache.search(Person.class, "addresses.place", "Norway", "Bergen")));
        assertTrue(cache.search(Person.class, "addresses.place", "Norway", "Oslo").isEmpty());
    }
}
