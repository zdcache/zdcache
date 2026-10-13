package io.github.zdcache;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UniversitySearchExamplesTest {
    enum Status { ENROLLED, COMPLETED, WITHDRAWN }
    enum Grade { A, B, C, D, E, F }

    record Instructor(String code, String name) {}
    record Course(String code, String title, int credits, Instructor[] instructors) {}
    record Exam(LocalDate date, Grade grade) {}
    record Enrollment(Course course, Status status, List<Exam> exams) {}
    record Scholarship(String program, int amount) {}
    record Student(long id, String fullName, String university, int startYear, Scholarship scholarship,
                   String[] languages, List<Enrollment> enrollments) {}

    static final class University {
        public final long id;
        public final String code;
        public final String name;
        public final String country;

        University(long id, String code, String name, String country) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.country = country;
        }
    }

    static final Instructor TURING = new Instructor("I01", "Alan Turing");
    static final Instructor HOPPER = new Instructor("I02", "Grace Hopper");
    static final Instructor LOVELACE = new Instructor("I03", "Ada Lovelace");
    static final Instructor KNUTH = new Instructor("I04", "Donald Knuth");

    static final Course ALGORITHMS = new Course("INF100", "Algorithms", 10, new Instructor[] {KNUTH, TURING});
    static final Course DATABASES = new Course("INF200", "Databases", 10, new Instructor[] {HOPPER});
    static final Course COMPILERS = new Course("INF300", "Compilers", 5, new Instructor[] {HOPPER, KNUTH});
    static final Course CALCULUS = new Course("MAT100", "Calculus", 10, new Instructor[] {LOVELACE});

    static final List<Student> STUDENTS = List.of(
        new Student(1, "Emma Hansen", "UIO", 2021, new Scholarship("Erasmus", 3000), new String[] {"no", "en"}, List.of(
            new Enrollment(ALGORITHMS, Status.COMPLETED, List.of(new Exam(LocalDate.of(2022, 6, 1), Grade.A))),
            new Enrollment(DATABASES, Status.ENROLLED, List.of()))),
        new Student(2, "Liam Olsen", "UIO", 2022, new Scholarship("Fulbright", 5000), new String[] {"en"}, List.of(
            new Enrollment(ALGORITHMS, Status.ENROLLED, List.of()),
            new Enrollment(COMPILERS, Status.COMPLETED, List.of(new Exam(LocalDate.of(2023, 12, 10), Grade.B))))),
        new Student(3, "Nora Hansen Berg", "NTNU", 2020, null, new String[] {"no", "de"}, List.of(
            new Enrollment(CALCULUS, Status.COMPLETED, List.of(new Exam(LocalDate.of(2021, 5, 20), Grade.C))),
            new Enrollment(DATABASES, Status.WITHDRAWN, List.of()))),
        new Student(4, "Oliver Berg", "NTNU", 2023, new Scholarship("Erasmus", 4500), new String[] {"no"}, List.of(
            new Enrollment(COMPILERS, Status.ENROLLED, List.of()),
            new Enrollment(ALGORITHMS, Status.COMPLETED, List.of(new Exam(LocalDate.of(2024, 1, 15), Grade.A))))),
        new Student(5, "Sofie Lund", "UIB", 2021, new Scholarship("Erasmus", 2000), new String[] {"sv", "en"}, List.of(
            new Enrollment(DATABASES, Status.COMPLETED, List.of(new Exam(LocalDate.of(2022, 12, 1), Grade.B))))),
        new Student(6, "Lucas Dahl", "UIB", 2024, null, new String[] {"en"}, List.of()));

    static final List<University> UNIVERSITIES = List.of(
        new University(1, "UIO", "University of Oslo", "Norway"),
        new University(2, "NTNU", "Norwegian University of Science and Technology", "Norway"),
        new University(3, "UIB", "University of Bergen", "Norway"),
        new University(4, "KTH", "KTH Royal Institute of Technology", "Sweden"));

    static final IdentityDimension<Student, Long> STUDENT_ID = IdentityDimension.of(Student.class, "id", Student::id);
    static final IdentityDimension<University, Long> UNIVERSITY_ID =
        IdentityDimension.of(University.class, "id", university -> university.id);

    private static ZDCache students(SearchSpecification<Student, Long> spec) {
        var cache = ZDCache.builder().build();
        STUDENTS.forEach(student -> cache.put(student, spec));
        return cache;
    }

    private static List<Long> ids(List<Student> students) {
        return students.stream().map(Student::id).sorted().toList();
    }

    private static String programOf(Student student) {
        return student.scholarship() == null ? null : student.scholarship().program();
    }

    @Test void rootPropertyIsSearchedByPrefix() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .prefix(Student::fullName)
            .build());

        assertEquals(List.of(1L), ids(cache.search(Student::fullName, "emma")));
        assertEquals(List.of(2L, 6L), ids(cache.search(Student.class, "fullName", "L")));
        assertTrue(cache.search(Student::fullName, "hansen").isEmpty());
    }

    @Test void allEntitiesSharingAValueAreFoundWithAnExactDimension() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .exact("university")
            .build());

        assertEquals(List.of(3L, 4L), ids(cache.search(Student.class, "university", "NTNU")));
        assertTrue(cache.search(Student.class, "university", "NT").isEmpty());
    }

    @Test void optionalNestedObjectIsTraversedWithEach() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .each("scholarship", scholarship -> scholarship
                .exact("program")
                .range("amount"))
            .build());

        assertEquals(List.of(1L, 4L, 5L), ids(cache.search(Student.class, "scholarship.program", "Erasmus")));
        assertEquals(List.of(1L, 2L, 4L), ids(cache.search(Student.class, "scholarship.amount", ValueRange.between(3000, 5000))));
    }

    @Test void listOfObjectsIsTraversedWithEach() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .each("enrollments", enrollment -> enrollment.exact("status"))
            .build());

        assertEquals(List.of(3L), ids(cache.search(Student.class, "enrollments.status", Status.WITHDRAWN)));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(cache.search(Student.class, "enrollments.status", Status.COMPLETED)));
    }

    @Test void arraysAreTraversedThroughAnExtractor() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .exactEach("languages", student -> Arrays.asList(student.languages()))
            .build());

        assertEquals(List.of(3L), ids(cache.search(Student.class, "languages", "de")));
        assertEquals(List.of(1L, 2L, 5L, 6L), ids(cache.search(Student.class, "languages", "en")));
    }

    @Test void valuesSeveralLevelsDownAreReachedByChainingEach() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .each(Student::enrollments, enrollment -> enrollment
                .each("instructors", e -> Arrays.asList(e.course().instructors()), instructor -> instructor
                    .exact(Instructor::code)
                    .prefix(Instructor::name)))
            .build());

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(cache.search(Student.class, "enrollments.instructors.name", "grace")));
        assertEquals(List.of(3L), ids(cache.search(Student.class, "enrollments.instructors.name", "ada")));
        assertEquals(List.of(1L, 2L, 4L), ids(cache.search(Student.class, "enrollments.instructors.code", "I04")));
    }

    @Test void entityMatchingThroughSeveralElementsIsReturnedOnce() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .exactEach("instructor", student -> student.enrollments().stream()
                .flatMap(enrollment -> Arrays.stream(enrollment.course().instructors()))
                .map(Instructor::code)
                .toList())
            .build());

        List<Student> taughtByKnuth = cache.search(Student.class, "instructor", "I04");

        assertEquals(3, taughtByKnuth.size());
        assertEquals(List.of(1L, 2L, 4L), ids(taughtByKnuth));
    }

    @Test void datesInListsInsideListsAreSearchableByRange() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .each(Student::enrollments, enrollment -> enrollment
                .each(Enrollment::exams, exam -> exam
                    .range(Exam::date)
                    .exact(Exam::grade)))
            .build());

        assertEquals(List.of(1L, 5L), ids(cache.search(Student.class, "enrollments.exams.date",
            ValueRange.between(LocalDate.of(2022, 1, 1), LocalDate.of(2022, 12, 31)))));
        assertEquals(List.of(1L, 4L), ids(cache.search(Student.class, "enrollments.exams.grade", Grade.A)));
    }

    @Test void compoundOfTwoRootProperties() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .compound("universityName", c -> c
                .exact(Student::university)
                .prefix(Student::fullName))
            .build());

        assertEquals(List.of(3L), ids(cache.search(Student.class, "universityName", "NTNU", "nora")));
        assertEquals(List.of(1L, 2L), ids(cache.search(Student.class, "universityName", "UIO", "")));
        assertTrue(cache.search(Student.class, "universityName", "UIB", "emma").isEmpty());
    }

    @Test void compoundOfANestedValueAndARootRangeSkipsEntitiesMissingAPart() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .compound("programSince", c -> c
                .exact("program", UniversitySearchExamplesTest::programOf)
                .range(Student::startYear))
            .build());

        assertEquals(List.of(1L, 4L, 5L), ids(cache.search(Student.class, "programSince", "Erasmus", ValueRange.between(2021, 2023))));
        assertEquals(List.of(2L), ids(cache.search(Student.class, "programSince", "Fulbright", 2022)));
        assertTrue(cache.search(Student.class, "programSince", "Erasmus", 2020).isEmpty());
    }

    @Test void compoundOfListElementsAndARootProperty() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .compound("courseAtUniversity", c -> c
                .exactEach("course", student -> student.enrollments().stream().map(e -> e.course().code()).toList())
                .exact(Student::university))
            .build());

        assertEquals(List.of(1L), ids(cache.search(Student.class, "courseAtUniversity", "INF200", "UIO")));
        assertEquals(List.of(4L), ids(cache.search(Student.class, "courseAtUniversity", "INF100", "NTNU")));
        assertTrue(cache.search(Student.class, "courseAtUniversity", "MAT100", "UIO").isEmpty());
    }

    @Test void conditionsOnTheSameListElementNeedACompoundInsideEach() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .each(Student::enrollments, enrollment -> enrollment
                .exact("course", e -> e.course().code())
                .exact(Enrollment::status)
                .compound("courseStatus", c -> c
                    .exact("course", e -> e.course().code())
                    .exact(Enrollment::status)))
            .build());

        assertEquals(List.of(1L, 2L, 4L), ids(cache.search(Student.class, "enrollments.course", "INF100")));
        assertEquals(List.of(1L, 2L, 4L), ids(cache.search(Student.class, "enrollments.status", Status.ENROLLED)));
        assertEquals(List.of(2L), ids(cache.search(Student.class, "enrollments.courseStatus", "INF100", Status.ENROLLED)));
    }

    @Test void wordsInATextAreSearchableWhenExtractedSeparately() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .prefix(Student::fullName)
            .prefixEach("nameWords", student -> List.of(student.fullName().split(" ")))
            .build());

        assertTrue(cache.search(Student::fullName, "hansen").isEmpty());
        assertEquals(List.of(1L, 3L), ids(cache.search(Student.class, "nameWords", "hansen")));
        assertEquals(List.of(3L, 4L), ids(cache.search(Student.class, "nameWords", "berg")));
    }

    @Test void nonTextValuesAreSearchedAsTextThroughAConvertingExtractor() {
        var cache = students(SearchSpecification.builder(STUDENT_ID)
            .exact(Student::startYear)
            .prefix("startYearText", student -> Integer.toString(student.startYear()))
            .build());

        assertEquals(List.of(6L), ids(cache.search(Student::startYear, 2024)));
        assertTrue(cache.search(Student::startYear, "2024").isEmpty());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), ids(cache.search(Student.class, "startYearText", "202")));
        assertEquals(List.of(1L, 5L), ids(cache.search(Student.class, "startYearText", "2021")));
    }

    @Test void eachTypeInTheCacheHasItsOwnSpecification() {
        var universitySpec = SearchSpecification.builder(UNIVERSITY_ID)
            .prefix("name")
            .exact("country")
            .build();
        var studentSpec = SearchSpecification.builder(STUDENT_ID)
            .exact(Student::university)
            .build();
        var cache = ZDCache.builder().build();
        UNIVERSITIES.forEach(university -> cache.put(university, universitySpec));
        STUDENTS.forEach(student -> cache.put(student, studentSpec));

        var named = cache.search(University.class, "name", "university of");
        assertEquals(List.of("UIB", "UIO"), named.stream().map(university -> university.code).sorted().toList());
        var bergen = cache.search(University.class, "name", "university of b").getFirst();
        assertEquals(List.of(5L, 6L), ids(cache.search(Student::university, bergen.code)));
        var swedish = cache.search(University.class, "country", "Sweden").getFirst();
        assertTrue(cache.search(Student::university, swedish.code).isEmpty());
    }
}
