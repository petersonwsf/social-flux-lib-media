package com.socialflux.media;

import com.socialflux.media.gif.GifSanitizer;
import com.socialflux.media.image.ImageProcessor;
import com.socialflux.media.storage.InMemoryMediaStorage;
import com.socialflux.media.storage.MediaStorage;
import com.socialflux.media.storage.S3MediaStorage;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Registra a mídia em qualquer serviço que tenha a biblioteca no classpath: limitador de processamentos simultâneos,
 * {@link ImageProcessor}, {@link GifSanitizer}, {@link MediaProcessor} e o {@link MediaStorage} escolhido por
 * {@code socialflux.media.storage} ({@code s3} exige endpoint e chaves; {@code memory} é só para testes).
 * Cada serviço continua dono das suas regras de acesso, do bucket e do formato de erro.
 */
@AutoConfiguration
@EnableConfigurationProperties(MediaProperties.class)
public class SocialFluxMediaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProcessingLimiter mediaProcessingLimiter(MediaProperties properties) {
        return new ProcessingLimiter(properties.maxConcurrentProcessing(), properties.acquireTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    ImageProcessor imageProcessor(MediaProperties properties, ProcessingLimiter limiter) {
        return new ImageProcessor(properties.image(), limiter);
    }

    @Bean
    @ConditionalOnMissingBean
    GifSanitizer gifSanitizer(MediaProperties properties, ProcessingLimiter limiter) {
        return new GifSanitizer(properties.gif(), limiter);
    }

    @Bean
    @ConditionalOnMissingBean
    MediaProcessor mediaProcessor(ImageProcessor images, GifSanitizer gifs) {
        return new MediaProcessor(images, gifs);
    }

    @Bean
    @ConditionalOnMissingBean
    MediaStorage mediaStorage(MediaProperties properties) {
        if (properties.storage() == MediaProperties.Storage.MEMORY) {
            return new InMemoryMediaStorage(Clock.systemUTC());
        }
        return S3MediaStorage.create(properties.s3());
    }
}
