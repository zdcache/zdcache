package io.github.zdcache;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextSearchExampleTest {
    record Person(long id, String name, String city) {}

    private static List<Long> ids(List<Person> people) { return people.stream().map(Person::id).sorted().toList(); }

    @Test @SuppressWarnings("unchecked")
    void theReadmeExamplesFindWhatTheySay() {
        var spec = SearchSpecification.builder(IdentityDimension.of(Person::id))
            .prefix(Person::name, TextStrategy.split(), TextStrategy.contains())
            .exact(Person::city)
            .build();
        var cache = ZDCache.builder().build();
        cache.put(new Person(1, "Anna Maria Olsen", "Oslo"), spec);
        cache.put(new Person(2, "Anna Olsen", "Oslo"));
        cache.put(new Person(3, "Per Andersen", "Bergen"));

        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, Person::name, "anna")));
        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, Person::name, Match.words("ols anna"))));
        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, Person::name, Match.fuzzy("ana olsne"))));
        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, Person::name, Match.fuzzy("olsne", 1))));
        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, Person::name, Match.contains("lse"))));
        assertEquals(List.of(3L), ids(cache.search(Person.class, Person::name, Match.contains("ders"))));
        assertEquals(List.of(3L), ids(cache.search(Person.class, Person::name, Match.fuzzy("andersn"))));
        var ranked = cache.searchRanked(Person::name, Match.words("anna olsen"));
        assertEquals(2L, ranked.getFirst().result().id());
        assertEquals(RankLevel.EXACT, ranked.getFirst().rankLevel());

        var current = (SearchSpecification<Person, Long>) cache.getSpecification(Person.class);
        cache.updateSpecification(Person.class, current.withStrategies(Person::name, TextStrategy.split()));
        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, Person::name, Match.words("anna"))));
    }

    @Test void nonAlphanumericSplitFindsTheSecondPartOfAHyphenatedName() {
        var spec = SearchSpecification.builder(IdentityDimension.of(Person::id))
            .prefix(Person::name, TextStrategy.split(Split.NON_ALPHANUMERIC)).build();
        var cache = ZDCache.builder().build();
        cache.put(new Person(1, "Kari Hansen-Olsen", "Oslo"), spec);
        assertEquals(List.of(1L), ids(cache.search(Person.class, Person::name, Match.words("olsen"))));
    }

    @Test void aStrategyTheDimensionDoesNotDeclareIsRefused() {
        var cache = ZDCache.builder().build();
        cache.put(new Person(1, "Anna", "Oslo"), SearchSpecification.builder(IdentityDimension.of(Person::id)).prefix(Person::name).build());
        assertThrows(UndeclaredSearchStrategyException.class, () -> cache.search(Person.class, Person::name, Match.contains("nn")));
    }
}
