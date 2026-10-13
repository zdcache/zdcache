package io.github.zdcache;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NullValueTraversalTest {
    record Room(String building, List<String> features) {}
    record Booking(long id, String title, List<Room> rooms, List<String> attendees) {}

    static final IdentityDimension<Booking, Long> BOOKING_ID = IdentityDimension.of(Booking.class, "id", Booking::id);

    private static SearchSpecification<Booking, Long> spec() {
        return SearchSpecification.builder(BOOKING_ID)
            .prefix("title", Booking::title)
            .exactEach("attendees", Booking::attendees)
            .each("rooms", Booking::rooms, room -> room
                .exact("building", Room::building)
                .exactEach("features", Room::features)
                .compound("buildingFeature", c -> c
                    .exact("building", Room::building)
                    .exactEach("feature", Room::features)))
            .build();
    }

    private static List<Long> ids(List<Booking> bookings) {
        return bookings.stream().map(Booking::id).sorted().toList();
    }

    @Test void nullCollectionsAreTreatedAsEmpty() {
        var cache = ZDCache.builder().build();
        cache.put(new Booking(1, "Standup", null, null), spec());
        cache.put(new Booking(2, "Retro", List.of(new Room("A", null)), List.of("ana")));

        assertEquals(List.of(1L), ids(cache.search(Booking.class, "title", "stand")));
        assertEquals(List.of(2L), ids(cache.search(Booking.class, "rooms.building", "A")));
        assertTrue(cache.search(Booking.class, "rooms.buildingFeature", "A", "projector").isEmpty());
    }

    @Test void nullElementsAndValuesAreSkipped() {
        var cache = ZDCache.builder().build();
        cache.put(new Booking(1, "Planning",
            Arrays.asList(null, new Room(null, List.of("projector")), new Room("B", Arrays.asList("whiteboard", null))),
            Arrays.asList("ana", null)), spec());

        assertEquals(List.of(1L), ids(cache.search(Booking.class, "attendees", "ana")));
        assertEquals(List.of(1L), ids(cache.search(Booking.class, "rooms.features", "projector")));
        assertEquals(List.of(1L), ids(cache.search(Booking.class, "rooms.building", "B")));
        assertEquals(List.of(1L), ids(cache.search(Booking.class, "rooms.buildingFeature", "B", "whiteboard")));
        assertTrue(cache.search(Booking.class, "rooms.buildingFeature", "B", "projector").isEmpty());
    }

    @Test void nullScalarValuesAreNotIndexed() {
        var cache = ZDCache.builder().build();
        cache.put(new Booking(1, null, List.of(), List.of()), spec());
        cache.put(new Booking(2, "Demo", List.of(), List.of()));

        assertEquals(List.of(1L, 2L), ids(cache.values(Booking.class)));
        assertEquals(List.of(2L), ids(cache.search(Booking.class, "title", "")));
    }
}
