package io.github.zdcache;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeResolutionTest {
    static class Person {
        final long id;
        final String name;
        Person(long id, String name) { this.id = id; this.name = name; }
    }
    static class Employee extends Person {
        Employee(long id, String name) { super(id, name); }
    }
    static class Manager extends Employee {
        Manager(long id, String name) { super(id, name); }
    }

    interface Named { String name(); }
    interface Tagged { String tag(); }
    interface Pet extends Named {}
    record Dog(String name) implements Pet {}
    record Label(String name, String tag) implements Named, Tagged {}
    static class NamedPerson extends Person implements Named {
        NamedPerson(long id, String name) { super(id, name); }
        @Override public String name() { return name; }
    }

    static final IdentityDimension<Person, Long> PERSON_ID =
        IdentityDimension.of(Person.class, "id", (Person p) -> p.id);
    static final IdentityDimension<Employee, Long> EMPLOYEE_ID =
        IdentityDimension.of(Employee.class, "id", (Employee e) -> e.id);

    private static SearchSpecification<Person, Long> personSpec() {
        return SearchSpecification.builder(PERSON_ID).prefix("name", p -> p.name).build();
    }

    private static SearchSpecification<Employee, Long> employeeSpec() {
        return SearchSpecification.builder(EMPLOYEE_ID).prefix("name", e -> e.name).build();
    }

    private static SearchSpecification<Named, String> namedSpec() {
        return SearchSpecification.builder(IdentityDimension.of(Named.class, "name", Named::name))
            .prefix("name", Named::name).build();
    }

    private static List<Long> ids(List<? extends Person> people) {
        return people.stream().map(p -> p.id).sorted().toList();
    }

    @Test void subtypeWithoutSpecificationIsFoundThroughSuperclassSpecification() {
        var cache = ZDCache.builder().build();
        cache.register(personSpec());
        cache.put(new Person(1, "Simon"));
        cache.put(new Employee(2, "Siri"));

        assertEquals(2, cache.size(Person.class));
        assertInstanceOf(Employee.class, cache.get(Person.class, 2L));
        assertEquals("Siri", cache.get(Employee.class, 2L).name);
        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, "name", "s")));
    }

    @Test void subtypeLookupsOnlyReturnInstancesOfTheRequestedType() {
        var cache = ZDCache.builder().build();
        cache.register(personSpec());
        cache.put(new Person(1, "Simon"));
        cache.put(new Employee(2, "Siri"));

        assertNull(cache.get(Employee.class, 1L));
        assertEquals(List.of(2L), ids(cache.search(Employee.class, "name", "s")));
        assertNull(cache.remove(Employee.class, 1L));
        assertNotNull(cache.get(Person.class, 1L));
        assertEquals(2L, cache.remove(Employee.class, 2L).id);
        assertNull(cache.get(Person.class, 2L));
    }

    @Test void walkUsesNearestSuperclassWithSpecification() {
        var cache = ZDCache.builder().build();
        cache.register(personSpec());
        cache.register(employeeSpec());
        cache.put(new Manager(3, "Mona"));

        assertEquals(1, cache.size(Employee.class));
        assertEquals(0, cache.size(Person.class));
        assertNotNull(cache.get(Manager.class, 3L));
    }

    @Test void redirectWinsOverNearerSuperclassSpecification() {
        var cache = ZDCache.builder().build();
        var personSpec = personSpec();
        cache.register(employeeSpec());
        cache.register(SearchSpecification.lookupAs(Manager.class, personSpec));
        cache.put(new Manager(3, "Mona"));

        assertEquals(1, cache.size(Person.class));
        assertEquals(0, cache.size(Employee.class));
        assertEquals(3L, cache.get(Manager.class, 3L).id);
        assertEquals(List.of(3L), ids(cache.search(Manager.class, "name", "mo")));
    }

    @Test void redirectAppliesToSubtypesFoundByWalking() {
        var cache = ZDCache.builder().build();
        cache.register(SearchSpecification.lookupAs(Employee.class, personSpec()));
        cache.put(new Manager(3, "Mona"));

        assertEquals(1, cache.size(Person.class));
        assertNotNull(cache.get(Employee.class, 3L));
    }

    @Test void redirectRegistersItsTargetSpecification() {
        var cache = ZDCache.builder().build();
        var personSpec = personSpec();
        cache.register(SearchSpecification.lookupAs(Employee.class, personSpec));

        assertSame(personSpec, cache.getSpecification(Person.class));
        assertNull(cache.getSpecification(Employee.class));
    }

    @Test void redirectMustPointToAProperSupertype() {
        assertThrows(IllegalArgumentException.class,
            () -> SearchSpecification.lookupAs(Person.class, personSpec()));
    }

    @Test void specificationAndRedirectForTheSameTypeConflict() {
        var cache = ZDCache.builder().build();
        var personSpec = personSpec();
        cache.register(employeeSpec());
        assertThrows(ConflictingSearchSpecificationException.class,
            () -> cache.register(SearchSpecification.lookupAs(Employee.class, personSpec)));

        var other = ZDCache.builder().build();
        other.register(SearchSpecification.lookupAs(Employee.class, personSpec));
        assertThrows(ConflictingSearchSpecificationException.class, () -> other.register(employeeSpec()));
        assertThrows(ConflictingSearchSpecificationException.class,
            () -> other.put(new Employee(2, "Siri"), employeeSpec()));
        assertThrows(ConflictingSearchSpecificationException.class,
            () -> other.register(SearchSpecification.lookupAs(Employee.class, personSpec())));
        other.register(SearchSpecification.lookupAs(Employee.class, personSpec));
    }

    @Test void registeringADifferentSpecificationInstanceConflicts() {
        var cache = ZDCache.builder().build();
        var personSpec = personSpec();
        cache.register(personSpec);
        cache.register(personSpec);
        assertThrows(ConflictingSearchSpecificationException.class, () -> cache.register(personSpec()));
    }

    @Test void laterRegistrationReplacesAnEarlierWalkResult() {
        var cache = ZDCache.builder().build();
        cache.register(personSpec());
        cache.put(new Employee(2, "Siri"));
        assertNull(cache.get(Manager.class, 2L));

        cache.register(employeeSpec());
        cache.put(new Manager(3, "Mona"));

        assertEquals(1, cache.size(Employee.class));
        assertNotNull(cache.get(Manager.class, 3L));
        assertNull(cache.get(Manager.class, 2L));
    }

    @Test void walkFallsBackToInterfacesNearestFirst() {
        var cache = ZDCache.builder().build();
        cache.register(namedSpec());
        cache.put(new Dog("Rex"));

        assertEquals(1, cache.size(Named.class));
        assertEquals(new Dog("Rex"), cache.get(Dog.class, "Rex"));
        assertEquals(List.of(new Dog("Rex")), cache.search(Pet.class, "name", "r"));
    }

    @Test void superclassesAreCheckedBeforeInterfaces() {
        var cache = ZDCache.builder().build();
        cache.register(personSpec());
        cache.register(namedSpec());
        cache.put(new NamedPerson(4, "Nina"));

        assertEquals(1, cache.size(Person.class));
        assertEquals(0, cache.size(Named.class));
    }

    @Test void interfacesAtTheSameDistanceWithDifferentSpecificationsAreAmbiguous() {
        var cache = ZDCache.builder().build();
        var namedSpec = namedSpec();
        cache.register(namedSpec);
        cache.register(SearchSpecification.builder(IdentityDimension.of(Tagged.class, "tag", Tagged::tag))
            .build());

        assertThrows(AmbiguousSearchSpecificationException.class, () -> cache.put(new Label("a", "b")));

        cache.register(SearchSpecification.lookupAs(Label.class, namedSpec));
        cache.put(new Label("a", "b"));
        assertEquals(1, cache.size(Named.class));
        assertEquals(0, cache.size(Tagged.class));
    }

    @Test void explicitPutRejectsEntityOfAnotherType() {
        var cache = ZDCache.builder().build();
        cache.register(personSpec());
        assertThrows(IllegalArgumentException.class,
            () -> putUnchecked(cache, Employee.class, 1L, new Person(1, "Simon")));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putUnchecked(ZDCache cache, Class type, Object id, Object entity) {
        cache.put(type, id, entity);
    }

    @Test void unknownTypesStayUnknown() {
        var cache = ZDCache.builder().build();
        cache.register(personSpec());

        assertNull(cache.get(String.class, "x"));
        assertThrows(MissingIdentitySpecificationException.class, () -> cache.put(new Dog("Rex")));
        assertThrows(MissingSearchSpecificationStrategyException.class,
            () -> cache.search(Dog.class, "name", "r"));
    }
}
