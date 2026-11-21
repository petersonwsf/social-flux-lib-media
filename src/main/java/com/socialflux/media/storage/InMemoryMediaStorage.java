package com.socialflux.media.storage;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Armazenamento em memória para testes sem Docker. Nunca use em produção: some ao reiniciar. */
public class InMemoryMediaStorage implements MediaStorage {

    private record Entry(byte[] bytes, String contentType, Instant lastModified) {
    }

    private final Map<String, Entry> objects = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryMediaStorage(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void put(String bucket, String key, byte[] bytes, String contentType) {
        objects.put(id(bucket, key), new Entry(bytes.clone(), contentType, clock.instant()));
    }

    @Override
    public Optional<StoredObject> get(String bucket, String key) {
        Entry entry = objects.get(id(bucket, key));
        return entry == null ? Optional.empty() : Optional.of(new StoredObject(entry.bytes().clone(), entry.contentType()));
    }

    @Override
    public void delete(String bucket, String key) {
        objects.remove(id(bucket, key));
    }

    @Override
    public boolean exists(String bucket, String key) {
        return objects.containsKey(id(bucket, key));
    }

    @Override
    public List<StoredKey> list(String bucket, String prefix) {
        String start = bucket + "/" + prefix;
        return objects.entrySet().stream()
                .filter(e -> e.getKey().startsWith(start))
                .map(e -> new StoredKey(e.getKey().substring(bucket.length() + 1), e.getValue().lastModified()))
                .sorted(Comparator.comparing(StoredKey::key))
                .toList();
    }

    /** Para testes: grava com uma data arbitrária (simula objeto antigo). */
    public void putAt(String bucket, String key, byte[] bytes, String contentType, Instant lastModified) {
        objects.put(id(bucket, key), new Entry(bytes.clone(), contentType, lastModified));
    }

    /** Para testes: quantos objetos há no bucket. */
    public int count(String bucket) {
        return (int) objects.keySet().stream().filter(k -> k.startsWith(bucket + "/")).count();
    }

    /** Para testes: esvazia tudo. */
    public void clear() {
        objects.clear();
    }

    private static String id(String bucket, String key) {
        return bucket + "/" + key;
    }
}
