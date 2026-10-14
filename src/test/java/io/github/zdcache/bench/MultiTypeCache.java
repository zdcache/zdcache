package io.github.zdcache.bench;

import io.github.zdcache.IdentityDimension;
import io.github.zdcache.Locking;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.ZDCache;
import java.util.List;
import java.util.Random;

public final class MultiTypeCache {
    public static final int TYPES = 4;

    public interface Account {
        long id();
        String name();
        String city();
        int balance();
    }

    public record Customer(long id, String name, String city, int balance) implements Account {}
    public record Vendor(long id, String name, String city, int balance) implements Account {}
    public record Warehouse(long id, String name, String city, int balance) implements Account {}
    public record Carrier(long id, String name, String city, int balance) implements Account {}

    private interface Factory {
        Account create(long id, String name, String city, int balance);
    }

    private record Kind(Class<? extends Account> type, Factory factory) {}

    private static final List<Kind> KINDS = List.of(
        new Kind(Customer.class, Customer::new),
        new Kind(Vendor.class, Vendor::new),
        new Kind(Warehouse.class, Warehouse::new),
        new Kind(Carrier.class, Carrier::new));

    private final ZDCache cache;
    private final Account[][][] versions;

    public MultiTypeCache(Locking locking, int size) {
        cache = ZDCache.builder().defaultMaxPerType(size).locking(locking).build();
        versions = new Account[TYPES][2][size];
        Random random = new Random(42);
        for (int type = 0; type < TYPES; type++) {
            register(KINDS.get(type).type());
            for (int index = 0; index < size; index++) {
                char[] name = new char[6];
                for (int c = 0; c < name.length; c++) name[c] = (char) ('a' + random.nextInt(26));
                String city = Dataset.city(random.nextInt(Dataset.CITIES));
                for (int version = 0; version < 2; version++) {
                    versions[type][version][index] = KINDS.get(type).factory().create(index, new String(name), city, version);
                }
                cache.put(versions[type][0][index]);
            }
        }
    }

    private <T extends Account> void register(Class<T> type) {
        cache.register(SearchSpecification.builder(IdentityDimension.of(type, "id", Account::id))
            .prefix("name", Account::name)
            .exact("city", Account::city)
            .build());
    }

    public Account read(int type, int index) {
        return cache.get(KINDS.get(type).type(), (long) index);
    }

    public void write(int type, int index, int version) {
        cache.put(versions[type][version][index]);
    }

    public Account transfer(int from, int to, int index, int version, boolean atomically) {
        if (!atomically) return readAndWriteBoth(from, to, index, version);
        return cache.atomically(() -> readAndWriteBoth(from, to, index, version));
    }

    private Account readAndWriteBoth(int from, int to, int index, int version) {
        Account read = read(from, index);
        cache.put(versions[from][version][index]);
        cache.put(versions[to][version][index]);
        return read;
    }

    public ZDCache cache() {
        return cache;
    }
}
