package io.github.zdcache;

import io.github.zdcache.store.IdentityStore;
import io.github.zdcache.store.IdentityStoreFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntriesTest {
    record Person(Long id, String name, String city) {}

    static final IdentityDimension<Person, Long> ID = IdentityDimension.of(Person.class, "id", Person::id);
    static final SearchSpecification<Person, Long> INDEXED = SearchSpecification.builder(ID).exact(Person::city).build();

    private final List<Object> putEntries = new ArrayList<>();

    private IdentityStoreFactory recording() {
        return new IdentityStoreFactory() {
            @SuppressWarnings("unchecked")
            @Override public <ID, T> IdentityStore<ID, T> create(int maxEntriesPerType) {
                IdentityStore<ID, T> store = IdentityStoreFactory.bufferedLru().create(maxEntriesPerType);
                return (IdentityStore<ID, T>) Proxy.newProxyInstance(IdentityStore.class.getClassLoader(),
                    new Class<?>[] {IdentityStore.class}, (proxy, method, args) -> {
                        if (method.getName().equals("put")) putEntries.add(args[1]);
                        try {
                            return method.invoke(store, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            }
        };
    }

    private void putTwice(ZDCache cache) {
        cache.put(new Person(1L, "Ada", "Oslo"), INDEXED);
        cache.put(new Person(1L, "Ada", "Bergen"));
        assertEquals(List.of(1L), cache.search(Person.class, "city", "Bergen").stream().map(Person::id).toList());
        assertEquals(2, putEntries.size());
    }

    @Test void anIndexedTypeKeepsOneEntryPerIdByDefault() {
        putTwice(ZDCache.builder().identityStore(recording()).build());
        assertSame(putEntries.get(0), putEntries.get(1));
    }

    @Test void perPutCreatesAnEntryForEveryPut() {
        putTwice(ZDCache.builder().identityStore(recording()).entries(Entries.PER_PUT).build());
        assertNotSame(putEntries.get(0), putEntries.get(1));
    }

    @Test void entriesAreRequired() {
        assertThrows(NullPointerException.class, () -> ZDCache.builder().entries(null));
    }
}
