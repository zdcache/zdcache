package io.github.zdcache;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvictionPolicyTest {
    static class Person {
        final long id;
        final String name;
        Person(long id, String name) { this.id = id; this.name = name; }
    }
    static class Employee extends Person {
        Employee(long id, String name) { super(id, name); }
    }

    static final IdentityDimension<Person, Long> PERSON_ID =
        IdentityDimension.of(Person.class, "id", (Person p) -> p.id);

    private static SearchSpecification<Person, Long> spec() {
        return SearchSpecification.builder(PERSON_ID).prefix("name").build();
    }

    private static ZDCache cacheOfThree(Person... people) {
        var cache = ZDCache.builder().defaultMaxPerType(3).build();
        cache.register(spec());
        for (Person person : people) cache.put(person);
        return cache;
    }

    private static List<Long> cachedIds(ZDCache cache) {
        return cache.search(Person.class, "name", "").stream().map(p -> p.id).sorted().toList();
    }

    @Test void searchHitsDoNotCountAsUse() {
        var cache = cacheOfThree(new Person(1, "Simon"), new Person(2, "Siri"), new Person(3, "Petter"));
        assertEquals(2, cache.search(Person.class, "name", "si").size());

        cache.put(new Person(4, "Nina"));

        assertEquals(List.of(2L, 3L, 4L), cachedIds(cache));
    }

    @Test void getCountsAsUse() {
        var cache = cacheOfThree(new Person(1, "Simon"), new Person(2, "Siri"), new Person(3, "Petter"));
        assertNotNull(cache.get(Person.class, 1L));

        cache.put(new Person(4, "Nina"));

        assertEquals(List.of(1L, 3L, 4L), cachedIds(cache));
    }

    @Test void subtypeLookupsThatDoNotMatchDoNotCountAsUse() {
        var cache = cacheOfThree(new Person(1, "Simon"), new Employee(2, "Siri"), new Person(3, "Petter"));
        assertNull(cache.get(Employee.class, 1L));
        assertNull(cache.remove(Employee.class, 1L));

        cache.put(new Person(4, "Nina"));

        assertEquals(List.of(2L, 3L, 4L), cachedIds(cache));
    }

    @Test void subtypeLookupsThatMatchCountAsUse() {
        var cache = cacheOfThree(new Employee(1, "Simon"), new Person(2, "Siri"), new Person(3, "Petter"));
        assertNotNull(cache.get(Employee.class, 1L));

        cache.put(new Person(4, "Nina"));

        assertEquals(List.of(1L, 3L, 4L), cachedIds(cache));
    }

    @Test void capacityForATypeStoredInAnotherTypesCacheFailsWhenResolved() {
        var cache = ZDCache.builder().capacity(Employee.class, 5).build();
        cache.register(spec());

        var thrown = assertThrows(CapacityNotApplicableException.class, () -> cache.put(new Employee(1, "Siri")));
        assertTrue(thrown.getMessage().contains("capacity(Person.class"));
    }

    @Test void capacityForARedirectedTypeFailsWhenTheRedirectIsRegistered() {
        var cache = ZDCache.builder().capacity(Employee.class, 5).build();
        assertThrows(CapacityNotApplicableException.class,
            () -> cache.register(SearchSpecification.lookupAs(Employee.class, spec())));
        assertNull(cache.getSpecification(Person.class));
    }

    @Test void capacityForATypeWithItsOwnSpecificationStillApplies() {
        var employeeSpec = SearchSpecification.builder(
            IdentityDimension.of(Employee.class, "id", (Employee e) -> e.id)).prefix("name").build();
        var cache = ZDCache.builder().capacity(Employee.class, 1).build();
        cache.register(employeeSpec);
        cache.put(new Employee(1, "Simon"));
        cache.put(new Employee(2, "Siri"));

        assertEquals(1, cache.size(Employee.class));
    }
}
