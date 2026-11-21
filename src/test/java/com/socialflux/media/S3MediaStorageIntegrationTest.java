package com.socialflux.media;

import com.socialflux.media.storage.MediaStorage;
import com.socialflux.media.storage.S3MediaStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste MANUAL contra um RustFS (ou MinIO/S3) real. Só roda com as variáveis definidas, por exemplo, com o compose no ar:
 * <pre>
 * RUSTFS_IT_ENDPOINT=http://localhost:9000 RUSTFS_IT_ACCESS_KEY=... RUSTFS_IT_SECRET_KEY=... RUSTFS_IT_BUCKET=post-media \
 *   mvn -pl libs/socialflux-media test -Dtest=S3MediaStorageIntegrationTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RUSTFS_IT_ENDPOINT", matches = ".+")
class S3MediaStorageIntegrationTest {

    @Test
    void putGetListDeleteAgainstRealServer() {
        MediaProperties.S3 config = new MediaProperties.S3(System.getenv("RUSTFS_IT_ENDPOINT"), "us-east-1",
                System.getenv("RUSTFS_IT_ACCESS_KEY"), System.getenv("RUSTFS_IT_SECRET_KEY"));
        String bucket = System.getenv().getOrDefault("RUSTFS_IT_BUCKET", "post-media");
        String key = MediaKeys.newKey("po", "gif", Clock.systemUTC());
        byte[] data = {'G', 'I', 'F', '8', '9', 'a', 1, 2, 3};

        try (S3MediaStorage storage = S3MediaStorage.create(config)) {
            storage.put(bucket, key, data, "image/gif");
            assertThat(storage.exists(bucket, key)).isTrue();
            MediaStorage.StoredObject object = storage.get(bucket, key).orElseThrow();
            assertThat(object.bytes()).isEqualTo(data);
            assertThat(object.contentType()).isEqualTo("image/gif");
            assertThat(storage.list(bucket, "po_")).anyMatch(k -> k.key().equals(key) && k.lastModified() != null);
            storage.delete(bucket, key);
            assertThat(storage.exists(bucket, key)).isFalse();
            assertThat(storage.get(bucket, key)).isEmpty();
            storage.delete(bucket, key);   // apagar de novo não falha
        }
    }
}
