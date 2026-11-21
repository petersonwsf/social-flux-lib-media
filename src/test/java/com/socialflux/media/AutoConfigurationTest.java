package com.socialflux.media;

import com.socialflux.media.gif.GifSanitizer;
import com.socialflux.media.image.ImageProcessor;
import com.socialflux.media.storage.InMemoryMediaStorage;
import com.socialflux.media.storage.MediaStorage;
import com.socialflux.media.storage.S3MediaStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SocialFluxMediaAutoConfiguration.class));

    @Test
    void memoryStorageAndProcessorsAreRegistered() {
        runner.withPropertyValues("socialflux.media.storage=memory", "socialflux.media.gif.max-frames=10")
                .run(context -> {
                    assertThat(context).hasSingleBean(ImageProcessor.class).hasSingleBean(GifSanitizer.class)
                            .hasSingleBean(MediaProcessor.class).hasSingleBean(ProcessingLimiter.class);
                    assertThat(context.getBean(MediaStorage.class)).isInstanceOf(InMemoryMediaStorage.class);
                    assertThat(context.getBean(MediaProperties.class).gif().maxFrames()).isEqualTo(10);
                    assertThat(context.getBean(MediaProperties.class).image().maxSide()).isEqualTo(1600);
                });
    }

    @Test
    void s3StorageIsTheDefaultAndRequiresEndpointAndKeys() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("socialflux.media.s3.endpoint=http://localhost:9000",
                        "socialflux.media.s3.access-key=TESTE", "socialflux.media.s3.secret-key=segredo")
                .run(context -> assertThat(context.getBean(MediaStorage.class)).isInstanceOf(S3MediaStorage.class));
    }

    @Test
    void invalidLimitsFailAtStartup() {
        runner.withPropertyValues("socialflux.media.storage=memory", "socialflux.media.max-concurrent-processing=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
