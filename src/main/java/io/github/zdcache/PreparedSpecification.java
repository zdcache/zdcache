package io.github.zdcache;

public interface PreparedSpecification {
    void commit();
    void abandon();
}
