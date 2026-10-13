package io.github.zdcache.bench;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.zdcache.IdentityDimension;
import io.github.zdcache.Recency;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.ValueRange;
import io.github.zdcache.ZDCache;
import io.github.zdcache.store.IdentityStoreFactory;
import com.google.common.cache.CacheBuilder;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.index.radix.RadixTreeIndex;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.QueryFactory;
import com.googlecode.cqengine.resultset.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import org.cache2k.Cache2kBuilder;
import java.util.function.Predicate;

public final class Subjects {
    public static final String CAFFEINE = "caffeine";
    public static final String ZDCACHE_LHM_KEYS = "zdcache-lhm-keys";
    public static final String ZDCACHE_CAFFEINE_KEYS = "zdcache-caffeine-keys";
    public static final String ZDCACHE_LHM_INDEXED = "zdcache-lhm-indexed";
    public static final String ZDCACHE_CAFFEINE_INDEXED = "zdcache-caffeine-indexed";
    public static final String ZDCACHE_LHM_INDEXED_LAMBDA = "zdcache-lhm-indexed-lambda";
    public static final String ZDCACHE_BUFFERED_KEYS = "zdcache-buffered-keys";
    public static final String ZDCACHE_BUFFERED_INDEXED = "zdcache-buffered-indexed";
    public static final String ZDCACHE_APPROXIMATE_KEYS = "zdcache-approximate-keys";
    public static final String ZDCACHE_APPROXIMATE_INDEXED = "zdcache-approximate-indexed";
    public static final String GUAVA = "guava";
    public static final String CACHE2K = "cache2k";
    public static final String CQENGINE = "cqengine";

    public static final List<String> ALL = List.of(CAFFEINE, ZDCACHE_LHM_KEYS, ZDCACHE_CAFFEINE_KEYS,
        ZDCACHE_LHM_INDEXED, ZDCACHE_CAFFEINE_INDEXED, ZDCACHE_LHM_INDEXED_LAMBDA,
        ZDCACHE_BUFFERED_KEYS, ZDCACHE_BUFFERED_INDEXED, ZDCACHE_APPROXIMATE_KEYS, ZDCACHE_APPROXIMATE_INDEXED,
        GUAVA, CACHE2K, CQENGINE);
    public static final List<String> SEARCHABLE = List.of(CAFFEINE, ZDCACHE_LHM_INDEXED, ZDCACHE_CAFFEINE_INDEXED,
        ZDCACHE_BUFFERED_INDEXED, GUAVA, CACHE2K, CQENGINE);

    private static final IdentityDimension<Person, Long> ID = IdentityDimension.of(Person.class, "id", Person::id);

    private Subjects() { }

    public static CacheSubject create(String name, int capacity) {
        return create(name, capacity, false);
    }

    public static CacheSubject create(String name, int capacity, boolean directCaffeineMaintenance) {
        return switch (name) {
            case CAFFEINE -> new CaffeineSubject(capacity, directCaffeineMaintenance);
            case ZDCACHE_LHM_KEYS -> zdcache(IdentityStoreFactory.linkedHashMap(), capacity, keysOnly());
            case ZDCACHE_CAFFEINE_KEYS -> zdcache(IdentityStoreFactory.caffeine(), capacity, keysOnly());
            case ZDCACHE_LHM_INDEXED -> zdcache(IdentityStoreFactory.linkedHashMap(), capacity, reflective());
            case ZDCACHE_CAFFEINE_INDEXED -> zdcache(IdentityStoreFactory.caffeine(), capacity, reflective());
            case ZDCACHE_LHM_INDEXED_LAMBDA -> zdcache(IdentityStoreFactory.linkedHashMap(), capacity, lambdas());
            case ZDCACHE_BUFFERED_KEYS -> zdcache(IdentityStoreFactory.bufferedLru(), capacity, keysOnly());
            case ZDCACHE_BUFFERED_INDEXED -> zdcache(IdentityStoreFactory.bufferedLru(), capacity, reflective());
            case ZDCACHE_APPROXIMATE_KEYS -> zdcache(IdentityStoreFactory.bufferedLru(), Recency.APPROXIMATE, capacity, keysOnly());
            case ZDCACHE_APPROXIMATE_INDEXED -> zdcache(IdentityStoreFactory.bufferedLru(), Recency.APPROXIMATE, capacity, reflective());
            case GUAVA -> new GuavaSubject(capacity);
            case CACHE2K -> new Cache2kSubject(capacity);
            case CQENGINE -> new CQEngineSubject();
            default -> throw new IllegalArgumentException("Unknown subject " + name + "; choose one of " + ALL);
        };
    }

    static SearchSpecification<Person, Long> keysOnly() {
        return SearchSpecification.builder(ID).build();
    }

    static SearchSpecification<Person, Long> reflective() {
        return SearchSpecification.builder(ID)
            .prefix("name")
            .exact("city")
            .range("age")
            .compound("cityAge", c -> c.exact("city").range("age"))
            .build();
    }

    static SearchSpecification<Person, Long> lambdas() {
        return SearchSpecification.builder(ID)
            .prefix("name", Person::name)
            .exact("city", Person::city)
            .range("age", Person::age)
            .compound("cityAge", c -> c.exact("city", Person::city).range("age", Person::age))
            .build();
    }

    private static CacheSubject zdcache(IdentityStoreFactory stores, int capacity, SearchSpecification<Person, Long> spec) {
        return zdcache(ZDCache.builder().identityStore(stores), capacity, spec);
    }

    private static CacheSubject zdcache(IdentityStoreFactory stores, Recency recency, int capacity,
                                        SearchSpecification<Person, Long> spec) {
        return zdcache(ZDCache.builder().identityStore(stores).recency(recency), capacity, spec);
    }

    private static CacheSubject zdcache(ZDCache.Builder builder, int capacity, SearchSpecification<Person, Long> spec) {
        ZDCache cache = builder.defaultMaxPerType(capacity).build();
        cache.register(spec);
        return new ZDCacheSubject(cache);
    }

    private record ZDCacheSubject(ZDCache cache) implements CacheSubject {
        @Override public void put(Person person) { cache.put(person); }
        @Override public Person get(long id) { return cache.get(Person.class, id); }
        @Override public Object root() { return cache; }
        @Override public List<Person> byCity(String city) { return cache.search(Person.class, "city", city); }
        @Override public List<Person> byNamePrefix(String prefix) { return cache.search(Person.class, "name", prefix); }
        @Override public List<Person> byAge(int from, int to) {
            return cache.search(Person.class, "age", ValueRange.between(from, to));
        }
        @Override public List<Person> byCityAndAge(String city, int from, int to) {
            return cache.search(Person.class, "cityAge", city, ValueRange.between(from, to));
        }
    }

    private interface ScanningSubject extends CacheSubject {
        Collection<Person> all();

        @Override default List<Person> byCity(String city) { return scan(p -> p.city().equals(city)); }
        @Override default List<Person> byNamePrefix(String prefix) { return scan(p -> p.name().startsWith(prefix)); }
        @Override default List<Person> byAge(int from, int to) { return scan(p -> p.age() >= from && p.age() <= to); }
        @Override default List<Person> byCityAndAge(String city, int from, int to) {
            return scan(p -> p.city().equals(city) && p.age() >= from && p.age() <= to);
        }

        private List<Person> scan(Predicate<Person> condition) {
            return all().stream().filter(condition).toList();
        }
    }

    private static final class GuavaSubject implements ScanningSubject {
        private final com.google.common.cache.Cache<Long, Person> cache;

        GuavaSubject(int capacity) {
            cache = CacheBuilder.newBuilder().maximumSize(capacity + capacity / 20).build();
        }

        @Override public void put(Person person) { cache.put(person.id(), person); }
        @Override public Person get(long id) { return cache.getIfPresent(id); }
        @Override public Object root() { return cache; }
        @Override public Collection<Person> all() { return cache.asMap().values(); }
    }

    private static final class Cache2kSubject implements ScanningSubject {
        private final org.cache2k.Cache<Long, Person> cache;

        Cache2kSubject(int capacity) {
            cache = Cache2kBuilder.of(Long.class, Person.class).entryCapacity(capacity).eternal(true).build();
        }

        @Override public void put(Person person) { cache.put(person.id(), person); }
        @Override public Person get(long id) { return cache.get(id); }
        @Override public Object root() { return cache; }
        @Override public Collection<Person> all() { return cache.asMap().values(); }
    }

    private static final class CQEngineSubject implements CacheSubject {
        private static final Attribute<Person, Long> ID = QueryFactory.attribute(Person.class, Long.class, "id", Person::id);
        private static final Attribute<Person, String> NAME = QueryFactory.attribute(Person.class, String.class, "name", Person::name);
        private static final Attribute<Person, String> CITY = QueryFactory.attribute(Person.class, String.class, "city", Person::city);
        private static final Attribute<Person, Integer> AGE = QueryFactory.attribute(Person.class, Integer.class, "age", Person::age);

        private final IndexedCollection<Person> people = new ConcurrentIndexedCollection<>();
        private final Object writes = new Object();

        CQEngineSubject() {
            people.addIndex(HashIndex.onAttribute(ID));
            people.addIndex(HashIndex.onAttribute(CITY));
            people.addIndex(NavigableIndex.onAttribute(AGE));
            people.addIndex(RadixTreeIndex.onAttribute(NAME));
        }

        @Override public void put(Person person) {
            synchronized (writes) {
                List<Person> previous = retrieve(QueryFactory.equal(ID, person.id()));
                people.update(previous, List.of(person));
            }
        }

        @Override public Person get(long id) {
            try (ResultSet<Person> found = people.retrieve(QueryFactory.equal(ID, id))) {
                Iterator<Person> iterator = found.iterator();
                return iterator.hasNext() ? iterator.next() : null;
            }
        }

        @Override public Object root() { return people; }
        @Override public List<Person> byCity(String city) { return retrieve(QueryFactory.equal(CITY, city)); }
        @Override public List<Person> byNamePrefix(String prefix) { return retrieve(QueryFactory.startsWith(NAME, prefix)); }
        @Override public List<Person> byAge(int from, int to) { return retrieve(QueryFactory.between(AGE, from, to)); }
        @Override public List<Person> byCityAndAge(String city, int from, int to) {
            return retrieve(QueryFactory.and(QueryFactory.equal(CITY, city), QueryFactory.between(AGE, from, to)));
        }

        private List<Person> retrieve(Query<Person> query) {
            try (ResultSet<Person> found = people.retrieve(query)) {
                List<Person> list = new ArrayList<>();
                for (Person person : found) list.add(person);
                return list;
            }
        }
    }

    private static final class CaffeineSubject implements CacheSubject {
        private final Cache<Long, Person> cache;

        CaffeineSubject(int capacity, boolean directMaintenance) {
            Caffeine<Object, Object> builder = Caffeine.newBuilder().maximumSize(capacity);
            if (directMaintenance) builder = builder.executor(Runnable::run);
            cache = builder.build();
        }

        @Override public void put(Person person) { cache.put(person.id(), person); }
        @Override public Person get(long id) { return cache.getIfPresent(id); }
        @Override public Object root() { return cache; }
        @Override public List<Person> byCity(String city) { return scan(p -> p.city().equals(city)); }
        @Override public List<Person> byNamePrefix(String prefix) { return scan(p -> p.name().startsWith(prefix)); }
        @Override public List<Person> byAge(int from, int to) { return scan(p -> p.age() >= from && p.age() <= to); }
        @Override public List<Person> byCityAndAge(String city, int from, int to) {
            return scan(p -> p.city().equals(city) && p.age() >= from && p.age() <= to);
        }

        private List<Person> scan(Predicate<Person> condition) {
            return cache.asMap().values().stream().filter(condition).toList();
        }
    }
}
