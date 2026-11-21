package com.socialflux.media;

import com.socialflux.media.MediaException.Reason;
import com.socialflux.media.storage.InMemoryMediaStorage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Limitador de simultâneos, chaves, detecção de formato e armazenamento em memória. */
class MediaSupportTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void limiterRefusesWhenSaturatedAndRecovers() throws Exception {
        ProcessingLimiter limiter = new ProcessingLimiter(1, Duration.ofMillis(100));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> busy = executor.submit(() -> limiter.run(() -> {
                holding.countDown();
                await(release);
                return "ok";
            }));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> limiter.run(() -> "segundo"))
                    .isInstanceOf(MediaException.class)
                    .extracting(e -> ((MediaException) e).reason()).isEqualTo(Reason.BUSY);

            release.countDown();
            assertThat(busy.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
            assertThat(limiter.run(() -> "depois")).isEqualTo("depois");
            assertThat(limiter.available()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void limiterReleasesPermitOnFailure() {
        ProcessingLimiter limiter = new ProcessingLimiter(1, Duration.ofMillis(50));
        assertThatThrownBy(() -> limiter.run(() -> {
            throw new IllegalStateException("falhou");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(limiter.available()).isEqualTo(1);
    }

    @Test
    void keysAreGeneratedAndValidatedStrictly() {
        String key = MediaKeys.newKey(MediaKeys.POST_PREFIX, "jpg", clock);
        assertThat(key).matches("^po_[0-9a-f-]{36}\\.jpg$");
        assertThat(MediaKeys.isValid("po", key)).isTrue();
        assertThat(MediaKeys.isValid("av", key)).isFalse();
        assertThat(MediaKeys.isValid("po", "po_../../etc/passwd.jpg")).isFalse();
        assertThat(MediaKeys.isValid("po", key.replace(".jpg", ".svg"))).isFalse();
        assertThat(MediaKeys.isValid("po", null)).isFalse();
        assertThat(MediaKeys.contentTypeOf("po_x.gif")).isEqualTo("image/gif");
        assertThat(MediaKeys.contentTypeOf("po_x.png")).isEqualTo("image/png");
        assertThat(MediaKeys.contentTypeOf("po_x.jpg")).isEqualTo("image/jpeg");
        assertThat(MediaKeys.uuidV7(clock).version()).isEqualTo(7);
        assertThatThrownBy(() -> MediaKeys.newKey("../", "jpg", clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaKeys.newKey("po", "exe", clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsFormatsByMagicBytes() {
        assertThat(MediaFormat.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo(MediaFormat.JPEG);
        assertThat(MediaFormat.detect("GIF89a-----".getBytes())).isEqualTo(MediaFormat.GIF);
        assertThat(MediaFormat.detect("RIFF0000WEBPVP8 ".getBytes())).isEqualTo(MediaFormat.WEBP);
        assertThat(MediaFormat.detect("RIFF0000AVI LIST".getBytes())).isEqualTo(MediaFormat.UNKNOWN);
        assertThat(MediaFormat.detect(new byte[0])).isEqualTo(MediaFormat.UNKNOWN);
        assertThat(MediaFormat.detect(null)).isEqualTo(MediaFormat.UNKNOWN);
    }

    @Test
    void inMemoryStorageBehavesLikeAnObjectStore() {
        InMemoryMediaStorage storage = new InMemoryMediaStorage(clock);
        byte[] data = {1, 2, 3};
        storage.put("b", "po_a.jpg", data, "image/jpeg");
        data[0] = 9;                                               // cópia defensiva
        assertThat(storage.get("b", "po_a.jpg")).hasValueSatisfying(o -> {
            assertThat(o.bytes()).containsExactly(1, 2, 3);
            assertThat(o.contentType()).isEqualTo("image/jpeg");
        });
        assertThat(storage.exists("b", "po_a.jpg")).isTrue();
        assertThat(storage.get("outro", "po_a.jpg")).isEmpty();
        storage.put("b", "av_b.jpg", data, "image/jpeg");
        assertThat(storage.list("b", "po_")).extracting(k -> k.key()).containsExactly("po_a.jpg");
        assertThat(storage.list("b", "po_").getFirst().lastModified()).isEqualTo(clock.instant());
        storage.delete("b", "po_a.jpg");
        storage.delete("b", "inexistente.jpg");
        assertThat(storage.exists("b", "po_a.jpg")).isFalse();
        assertThat(storage.count("b")).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
