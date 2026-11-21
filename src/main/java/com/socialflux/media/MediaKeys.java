package com.socialflux.media;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Chaves de objetos de mídia, SEMPRE geradas pelo servidor: {@code <prefixo>_<uuidv7>.<ext>} (por exemplo
 * {@code po_0192….jpg} para postagens e {@code av_0192….jpg} para avatares). O cliente nunca informa nome ou caminho; uma
 * chave recebida na URL só é aceita se casar exatamente com o formato.
 */
public final class MediaKeys {

    public static final String POST_PREFIX = "po";
    public static final String AVATAR_PREFIX = "av";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern PREFIX = Pattern.compile("^[a-z]{2}$");
    private static final Pattern EXTENSION = Pattern.compile("^(jpg|png|gif)$");
    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private MediaKeys() {
    }

    public static String newKey(String prefix, String extension, Clock clock) {
        if (!PREFIX.matcher(prefix).matches() || !EXTENSION.matcher(extension).matches()) {
            throw new IllegalArgumentException("Prefixo ou extensão de chave inválidos");
        }
        return prefix + "_" + uuidV7(clock) + "." + extension;
    }

    /** A chave tem o formato {@code <prefixo>_<uuid>.(jpg|png|gif)}? */
    public static boolean isValid(String prefix, String key) {
        return key != null && PREFIX.matcher(prefix).matches()
                && key.matches("^" + prefix + "_" + UUID_REGEX + "\\.(jpg|png|gif)$");
    }

    /** {@code Content-Type} a partir da extensão de uma chave válida. */
    public static String contentTypeOf(String key) {
        if (key.endsWith(".png")) {
            return ProcessedMedia.PNG;
        }
        if (key.endsWith(".gif")) {
            return ProcessedMedia.GIF_TYPE;
        }
        return ProcessedMedia.JPEG;
    }

    /** UUIDv7 (RFC 9562): ordenável no tempo. */
    public static UUID uuidV7(Clock clock) {
        long timestamp = clock.millis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long msb = (timestamp << 16) | (0x7L << 12) | (((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL));
        long lsb = 0x8000000000000000L | (ByteBuffer.wrap(random, 2, 8).getLong() & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(msb, lsb);
    }
}
