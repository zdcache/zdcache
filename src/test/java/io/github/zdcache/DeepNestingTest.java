package io.github.zdcache;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeepNestingTest {
    record Lesson(String room, LocalDate date) {}
    record Module(String code, List<Lesson> lessons) {}
    record Programme(String name, List<Module> modules) {}
    record Department(String code, List<Programme> programmes) {}
    record Faculty(String name, List<Department> departments) {}
    record Campus(long id, String name, List<Faculty> faculties) {}

    static final String LESSONS = "faculties.departments.programmes.modules.lessons";

    private static SearchSpecification<Campus, Long> spec() {
        return SearchSpecification.builder(IdentityDimension.of(Campus.class, "id", Campus::id))
            .each(Campus::faculties, faculty -> faculty
                .each(Faculty::departments, department -> department
                    .exact(Department::code)
                    .each(Department::programmes, programme -> programme
                        .each(Programme::modules, module -> module
                            .prefix(Module::code)
                            .each(Module::lessons, lesson -> lesson
                                .exact(Lesson::room)
                                .range(Lesson::date)
                                .compound("roomDate", c -> c.exact(Lesson::room).range(Lesson::date)))))))
            .build();
    }

    private static Campus campus(long id, int width, String roomPrefix, LocalDate start) {
        List<Faculty> faculties = new ArrayList<>();
        for (int f = 0; f < width; f++) {
            List<Department> departments = new ArrayList<>();
            for (int d = 0; d < width; d++) {
                List<Programme> programmes = new ArrayList<>();
                for (int p = 0; p < width; p++) {
                    List<Module> modules = new ArrayList<>();
                    for (int m = 0; m < width; m++) {
                        List<Lesson> lessons = new ArrayList<>();
                        for (int l = 0; l < width; l++) {
                            lessons.add(new Lesson(roomPrefix + "-" + f + d + p + m + l, start.plusDays(l)));
                        }
                        modules.add(new Module("MOD" + f + d + p + m, lessons));
                    }
                    programmes.add(new Programme("P" + f + d + p, modules));
                }
                departments.add(new Department("D" + f + d, programmes));
            }
            faculties.add(new Faculty("F" + f, departments));
        }
        return new Campus(id, "Campus " + id, faculties);
    }

    private static List<Long> ids(List<Campus> campuses) {
        return campuses.stream().map(Campus::id).sorted().toList();
    }

    @Test void dimensionsSixLevelsDownAreSearchable() {
        var cache = ZDCache.builder().build();
        cache.put(campus(1, 3, "A", LocalDate.of(2026, 1, 5)), spec());
        cache.put(campus(2, 3, "B", LocalDate.of(2026, 3, 2)));

        assertEquals(List.of(1L), ids(cache.search(Campus.class, LESSONS + ".room", "A-21012")));
        assertEquals(List.of(1L, 2L), ids(cache.search(Campus.class, "faculties.departments.code", "D12")));
        assertEquals(List.of(1L, 2L), ids(cache.search(Campus.class, "faculties.departments.programmes.modules.code", "mod12")));
        assertEquals(List.of(2L), ids(cache.search(Campus.class, LESSONS + ".date",
            ValueRange.between(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))));
        assertEquals(List.of(1L), ids(cache.search(Campus.class, LESSONS + ".roomDate", "A-00002", LocalDate.of(2026, 1, 7))));
        assertTrue(cache.search(Campus.class, LESSONS + ".roomDate", "A-00002", LocalDate.of(2026, 1, 5)).isEmpty());
    }

    @Test void entitiesMatchingThroughThousandsOfDeepElementsAreReturnedOnce() {
        var cache = ZDCache.builder().build();
        cache.put(campus(1, 4, "A", LocalDate.of(2026, 1, 5)), spec());

        var matches = cache.search(Campus.class, LESSONS + ".date", ValueRange.between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        assertEquals(1, matches.size());
        assertEquals(1, cache.search(Campus.class, LESSONS + ".room", "A-33333").size());
    }

    @Test void replacingAndRemovingCleansEveryDeepValue() {
        var cache = ZDCache.builder().build();
        cache.put(campus(1, 3, "A", LocalDate.of(2026, 1, 5)), spec());

        cache.put(campus(1, 2, "C", LocalDate.of(2026, 9, 1)));
        assertTrue(cache.search(Campus.class, LESSONS + ".room", "A-00000").isEmpty());
        assertTrue(cache.search(Campus.class, LESSONS + ".room", "C-11111").size() == 1);
        assertTrue(cache.search(Campus.class, "faculties.departments.code", "D22").isEmpty());

        cache.remove(Campus.class, 1L);
        assertTrue(cache.search(Campus.class, LESSONS + ".room", "C-11111").isEmpty());
        assertTrue(cache.search(Campus.class, "faculties.departments.programmes.modules.code", "").isEmpty());
    }
}
