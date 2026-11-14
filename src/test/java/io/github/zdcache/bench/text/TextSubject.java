package io.github.zdcache.bench.text;

import java.util.List;

public interface TextSubject extends AutoCloseable {
    String ZDCACHE = "zdcache";
    String LUCENE = "lucene";

    void load(List<Student> students);
    void put(Student student);
    List<Student> search(TextQuery query);
    Object root();
    @Override default void close() { }

    static TextSubject create(String name) {
        return switch (name) {
            case ZDCACHE -> new ZdcacheText();
            case LUCENE -> new LuceneText();
            default -> throw new IllegalArgumentException("Unknown text subject " + name);
        };
    }
}
