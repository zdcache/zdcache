package io.github.zdcache.bench;

import java.util.List;

public interface CacheSubject {
    void put(Person person);
    Person get(long id);
    Object root();

    default List<Person> byCity(String city) { throw new UnsupportedOperationException(); }
    default List<Person> byNamePrefix(String prefix) { throw new UnsupportedOperationException(); }
    default List<Person> byAge(int from, int to) { throw new UnsupportedOperationException(); }
    default List<Person> byCityAndAge(String city, int from, int to) { throw new UnsupportedOperationException(); }
}
