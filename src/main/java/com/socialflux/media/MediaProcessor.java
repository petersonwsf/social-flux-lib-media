package com.socialflux.media;

import com.socialflux.media.gif.GifSanitizer;
import com.socialflux.media.image.ImageProcessor;

/**
 * Porta de entrada para anexos que podem ser imagem estática OU GIF: decide pelos bytes mágicos e encaminha para o
 * {@link GifSanitizer} (GIF, sem redimensionar) ou para o {@link ImageProcessor} (PNG, JPEG, WebP: reduz e re-codifica).
 */
public class MediaProcessor {

    private final ImageProcessor images;
    private final GifSanitizer gifs;

    public MediaProcessor(ImageProcessor images, GifSanitizer gifs) {
        this.images = images;
        this.gifs = gifs;
    }

    public ProcessedMedia process(byte[] data) {
        if (data == null || data.length == 0) {
            throw new MediaException(MediaException.Reason.EMPTY, "Arquivo de imagem vazio");
        }
        return MediaFormat.detect(data) == MediaFormat.GIF ? gifs.sanitize(data) : images.fit(data);
    }
}
