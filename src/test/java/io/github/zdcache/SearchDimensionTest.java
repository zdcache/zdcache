package io.github.zdcache;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchDimensionTest {
    record Item(Long id, String city, Integer size, List<String> tags, Object raw) {}

    static final IdentityDimension<Item, Long> ITEM_ID = IdentityDimension.of(Item.class, "id", Item::id);

    private static Item item(String city, Integer size, List<String> tags) {
        return new Item(1L, city, size, tags, new Object());
    }

    private static SearchDimension<Item> dimension(SearchSpecification<Item, Long> spec, String path) {
        return spec.dimensions().stream().filter(d -> d.path().equals(path)).findFirst().orElseThrow();
    }

    private static final SearchSpecification<Item, Long> SPEC = SearchSpecification.builder(ITEM_ID)
        .exact("city", Item::city)
        .exactEach("tag", Item::tags)
        .compound("cityTag", c -> c.exact("city", Item::city).exactEach("tag", Item::tags))
        .compound("citySize", c -> c.exact("city", Item::city).range("size", Item::size))
        .build();

    @Test void aSingleValueIsReturnedAlone() {
        assertEquals(Set.of("Oslo"), dimension(SPEC, "city").values(item("Oslo", 3, List.of())));
    }

    @Test void valuesAreDeduplicatedInTheOrderFirstSeenAndNullsIgnored() {
        Set<Object> tags = dimension(SPEC, "tag").values(item("Oslo", 3, Arrays.asList("b", null, "a", "b", "a")));
        assertEquals(List.of("b", "a"), List.copyOf(tags));
    }

    @Test void missingValuesGiveAnEmptySet() {
        assertTrue(dimension(SPEC, "city").values(item(null, 3, List.of())).isEmpty());
        assertTrue(dimension(SPEC, "tag").values(item("Oslo", 3, null)).isEmpty());
        assertTrue(dimension(SPEC, "tag").values(item("Oslo", 3, Arrays.asList(null, null))).isEmpty());
    }

    @Test void extractedValuesCannotBeModified() {
        assertThrows(UnsupportedOperationException.class,
            () -> dimension(SPEC, "city").values(item("Oslo", 3, List.of())).add("Bergen"));
        assertThrows(UnsupportedOperationException.class,
            () -> dimension(SPEC, "tag").values(item("Oslo", 3, List.of("a", "b"))).add("c"));
    }

    @Test void compoundsCombineEveryDistinctValueOfEachPartInOrder() {
        assertEquals(List.of(List.of("Oslo", "b"), List.of("Oslo", "a")),
            List.copyOf(dimension(SPEC, "cityTag").values(item("Oslo", 3, List.of("b", "a", "b")))));
        assertEquals(List.of(List.of("Oslo", 3)), List.copyOf(dimension(SPEC, "citySize").values(item("Oslo", 3, List.of()))));
    }

    @Test void aCompoundWithAMissingPartHasNoValues() {
        assertTrue(dimension(SPEC, "cityTag").values(item("Oslo", 3, List.of())).isEmpty());
        assertTrue(dimension(SPEC, "citySize").values(item("Oslo", null, List.of())).isEmpty());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void aValueOfTheWrongKindNamesTheCompoundPart() {
        var spec = SearchSpecification.builder(ITEM_ID)
            .compound("cityRaw", c -> c.exact("city", Item::city).range("raw", (Function) (Function<Item, Object>) Item::raw))
            .build();
        var error = assertThrows(IllegalArgumentException.class,
            () -> dimension(spec, "cityRaw").values(item("Oslo", 3, List.of())));
        assertEquals("RANGE value must be Comparable at cityRaw.raw", error.getMessage());
    }
}
