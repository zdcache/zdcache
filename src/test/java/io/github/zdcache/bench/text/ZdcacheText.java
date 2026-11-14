package io.github.zdcache.bench.text;

import io.github.zdcache.IdentityDimension;
import io.github.zdcache.Match;
import io.github.zdcache.Ranked;
import io.github.zdcache.RankedPage;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.TextStrategy;
import io.github.zdcache.ZDCache;
import java.util.List;

final class ZdcacheText implements TextSubject {
    private final ZDCache cache = ZDCache.builder().defaultMaxPerType(Integer.MAX_VALUE).build();
    private final SearchSpecification<Student, Long> spec = SearchSpecification.builder(IdentityDimension.of(Student::id))
        .prefix(Student::name, TextStrategy.split(), TextStrategy.contains())
        .prefix("wholeName", Student::name)
        .build();

    ZdcacheText() { cache.register(spec); }

    @Override public void load(List<Student> students) { for (Student student : students) cache.put(student); }
    @Override public void put(Student student) { cache.put(student); }

    @Override public Best best(TextQuery query, int limit) {
        Object match = switch (query.kind().searched()) {
            case WORDS -> Match.words(query.text());
            case FUZZY_WORDS -> Match.fuzzy(query.text());
            case CONTAINS -> Match.contains(query.text());
            default -> throw new IllegalArgumentException(query.toString());
        };
        RankedPage<Student> page = cache.searchBest(Student::name, limit, match);
        return new Best(page.results().stream().map(Ranked::result).toList(), page.total());
    }

    @Override public List<Student> search(TextQuery query) {
        if (query.kind().best()) return best(query, TextQuery.BEST).results();
        return switch (query.kind()) {
            case PREFIX -> cache.search(Student.class, Student::name, query.text());
            case WORDS -> cache.search(Student.class, Student::name, Match.words(query.text()));
            case WORDS_RANKED -> cache.searchRanked(Student::name, Match.words(query.text())).stream().map(Ranked::result).toList();
            case FUZZY_WORDS_ONE_EDIT -> cache.search(Student.class, Student::name, Match.fuzzy(query.text(), 1));
            case FUZZY_WORDS -> cache.search(Student.class, Student::name, Match.fuzzy(query.text()));
            case FUZZY_WHOLE_ONE_EDIT, FUZZY_WHOLE_TWO_EDITS -> cache.search(Student.class, "wholeName", Match.fuzzy(query.text(), query.maxEdits()));
            case CONTAINS, CONTAINS_TWO -> cache.search(Student.class, Student::name, Match.contains(query.text()));
            default -> throw new IllegalArgumentException(query.toString());
        };
    }

    @Override public Object root() { return cache; }
}
