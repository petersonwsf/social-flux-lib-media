package com.socialflux.media;

/**
 * Resultado do processamento: o arquivo NOVO (nunca o enviado), o tipo, a extensão da chave e as dimensões finais (já com a
 * orientação EXIF aplicada).
 *
 * @param kind {@code IMAGE} (JPEG/PNG re-codificado) ou {@code GIF} (regravado pelo {@link GifSanitizer})
 */
public record ProcessedMedia(byte[] bytes, String contentType, String extension, int width, int height, Kind kind) {

    public enum Kind { IMAGE, GIF }

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String GIF_TYPE = "image/gif";
}
