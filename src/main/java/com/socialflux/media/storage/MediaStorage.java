package com.socialflux.media.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Armazenamento de objetos de mídia (bucket + chave). Implementações: {@link S3MediaStorage} (RustFS, MinIO ou S3) e
 * {@link InMemoryMediaStorage} (testes). Quem chama já validou a chave ({@code MediaKeys}); nenhum caminho de disco é
 * montado a partir de entrada do cliente.
 */
public interface MediaStorage {

    /** Objeto lido: bytes e o {@code Content-Type} gravado. */
    record StoredObject(byte[] bytes, String contentType) {
    }

    /** Chave listada com a data da última gravação (para a varredura de órfãos). */
    record StoredKey(String key, Instant lastModified) {
    }

    void put(String bucket, String key, byte[] bytes, String contentType);

    /** Vazio se o objeto não existir. */
    Optional<StoredObject> get(String bucket, String key);

    /** Silencioso se o objeto não existir. */
    void delete(String bucket, String key);

    boolean exists(String bucket, String key);

    /** Todas as chaves do bucket que começam com {@code prefix}. */
    List<StoredKey> list(String bucket, String prefix);
}
