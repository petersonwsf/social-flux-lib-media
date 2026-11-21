package com.socialflux.media;

import com.socialflux.media.MediaException.Reason;
import com.socialflux.media.image.ImageProcessor;
import com.socialflux.media.support.Images;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.zip.CRC32;

import static com.socialflux.media.support.Images.BL;
import static com.socialflux.media.support.Images.BR;
import static com.socialflux.media.support.Images.TL;
import static com.socialflux.media.support.Images.TR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageProcessorTest {

    private final ImageProcessor processor = processor(DataSize.ofMegabytes(5), 16_000_000, 1600);

    static ImageProcessor processor(DataSize maxBytes, long maxPixels, int maxSide) {
        return new ImageProcessor(new MediaProperties.Image(maxBytes, maxPixels, maxSide),
                new ProcessingLimiter(4, Duration.ofSeconds(5)));
    }

    // ------------------------------------------------------------------ orientação EXIF

    /**
     * Gera a imagem "como a câmera gravou" (o inverso da orientação) e confere que, depois do processamento, os quatro cantos
     * voltam às cores da imagem correta e a largura/altura gravadas são as da imagem em pé.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void appliesEveryExifOrientation(int orientation) {
        BufferedImage upright = Images.quadrants(80, 40);
        BufferedImage stored = inverse(upright, orientation);
        byte[] jpeg = Images.withExifOrientation(Images.jpeg(stored), orientation);

        ProcessedMedia result = processor.fit(jpeg);

        assertThat(result.width()).isEqualTo(80);
        assertThat(result.height()).isEqualTo(40);
        assertCorners(Images.read(result.bytes()));
    }

    @Test
    void readsOrientationFromPngAndWebp() {
        for (String name : new String[]{"png-orientation-6.png", "webp-orientation-6.webp"}) {
            ProcessedMedia result = processor.fit(Images.fixture(name));
            assertThat(result.width()).as(name).isEqualTo(80);
            assertThat(result.height()).as(name).isEqualTo(40);
            assertCorners(Images.read(result.bytes()));
        }
    }

    @Test
    void outputHasNoMetadata() {
        byte[] jpeg = Images.withExifOrientation(Images.jpeg(Images.quadrants(80, 40)), 1);
        assertThat(Images.contains(jpeg, "GPS-SECRETO")).isTrue();

        ProcessedMedia result = processor.fit(jpeg);

        assertThat(Images.contains(result.bytes(), "Exif")).isFalse();
        assertThat(Images.contains(result.bytes(), "GPS-SECRETO")).isFalse();
    }

    @Test
    void invalidOrientationIsIgnored() {
        byte[] jpeg = Images.withExifOrientation(Images.jpeg(Images.quadrants(80, 40)), 9);
        ProcessedMedia result = processor.fit(jpeg);
        assertThat(result.width()).isEqualTo(80);
        assertCorners(Images.read(result.bytes()));
    }

    // ------------------------------------------------------------------ formatos

    @Test
    void webpWithoutTransparencyBecomesJpeg() {
        ProcessedMedia result = processor.fit(Images.fixture("webp-opaque.webp"));
        assertThat(result.contentType()).isEqualTo(ProcessedMedia.JPEG);
        assertThat(result.extension()).isEqualTo("jpg");
        assertThat(result.kind()).isEqualTo(ProcessedMedia.Kind.IMAGE);
        assertThat(MediaFormat.detect(result.bytes())).isEqualTo(MediaFormat.JPEG);
        assertCorners(Images.read(result.bytes()));
    }

    @Test
    void webpWithTransparencyBecomesPng() {
        ProcessedMedia result = processor.fit(Images.fixture("webp-alpha.webp"));
        assertThat(result.contentType()).isEqualTo(ProcessedMedia.PNG);
        assertThat(result.extension()).isEqualTo("png");
        BufferedImage img = Images.read(result.bytes());
        assertThat(img.getRGB(79, 39) >>> 24).isZero();            // canto transparente continua transparente
        assertThat(Images.near(img.getRGB(2, 2), TL)).isTrue();
    }

    @Test
    void opaquePngWithAlphaChannelBecomesJpeg() {
        BufferedImage argb = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        argb.createGraphics().drawImage(Images.quadrants(20, 20), 0, 0, null);
        ProcessedMedia result = processor.fit(Images.png(argb));
        assertThat(result.contentType()).isEqualTo(ProcessedMedia.JPEG);
    }

    @Test
    void readsCmykJpeg() {
        ProcessedMedia result = processor.fit(Images.fixture("cmyk.jpg"));
        assertThat(result.contentType()).isEqualTo(ProcessedMedia.JPEG);
        BufferedImage img = Images.read(result.bytes());
        assertThat(img.getWidth()).isEqualTo(80);
        assertThat(isReddish(img.getRGB(5, 5))).as("canto superior esquerdo vermelho").isTrue();
    }

    @Test
    void refusesAnimatedWebpAndPng() {
        for (String name : new String[]{"webp-animated.webp", "png-animated.png"}) {
            assertThatThrownBy(() -> processor.fit(Images.fixture(name)))
                    .as(name).isInstanceOf(MediaException.class)
                    .extracting(e -> ((MediaException) e).reason()).isEqualTo(Reason.ANIMATED);
        }
    }

    @Test
    void refusesFakeAndUnsupportedFiles() {
        assertReason(() -> processor.fit("isto não é uma imagem".getBytes(StandardCharsets.UTF_8)), Reason.INVALID);
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, 0, 0, 0};
        assertReason(() -> processor.fit(gif), Reason.INVALID);
        assertReason(() -> processor.fit(new byte[0]), Reason.EMPTY);
        assertReason(() -> processor.fit(null), Reason.EMPTY);
    }

    @Test
    void refusesTruncatedFile() {
        byte[] png = Images.png(Images.quadrants(200, 200));
        assertReason(() -> processor.fit(Arrays.copyOf(png, png.length / 2)), Reason.INVALID);
    }

    @Test
    void refusesHugeDeclaredDimensionsBeforeDecoding() {
        assertReason(() -> processor.fit(pngHeaderOnly(20_000, 20_000)), Reason.DIMENSIONS_TOO_LARGE);
    }

    @Test
    void refusesFileOverTheByteLimit() {
        ImageProcessor small = processor(DataSize.ofBytes(100), 16_000_000, 1600);
        assertReason(() -> small.fit(Images.png(Images.quadrants(200, 200))), Reason.TOO_LARGE);
    }

    // ------------------------------------------------------------------ tamanho

    @Test
    void downscalesLargeImageKeepingAspectRatio() {
        ProcessedMedia result = processor.fit(Images.jpeg(Images.quadrants(4000, 1000)));
        assertThat(result.width()).isEqualTo(1600);
        assertThat(result.height()).isEqualTo(400);
        BufferedImage img = Images.read(result.bytes());
        assertThat(img.getWidth()).isEqualTo(1600);
        assertCorners(img);
    }

    @Test
    void neverUpscalesSmallImage() {
        ProcessedMedia result = processor.fit(Images.png(Images.quadrants(100, 50)));
        assertThat(result.width()).isEqualTo(100);
        assertThat(result.height()).isEqualTo(50);
    }

    @Test
    void squareJpegForAvatarsAppliesOrientation() {
        BufferedImage upright = Images.quadrants(80, 40);
        byte[] jpeg = Images.withExifOrientation(Images.jpeg(inverse(upright, 6)), 6);
        ProcessedMedia result = processor.squareJpeg(jpeg, 256);
        assertThat(result.width()).isEqualTo(256);
        assertThat(result.height()).isEqualTo(256);
        assertThat(result.contentType()).isEqualTo(ProcessedMedia.JPEG);
        BufferedImage img = Images.read(result.bytes());
        // recorte central 40×40 da imagem em pé: metade esquerda vermelho/azul, direita verde/amarelo
        assertThat(Images.near(img.getRGB(10, 10), TL)).isTrue();
        assertThat(Images.near(img.getRGB(245, 245), BR)).isTrue();
    }

    // ------------------------------------------------------------------ auxiliares

    private static void assertCorners(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int dx = Math.max(2, w / 10);
        int dy = Math.max(2, h / 10);
        assertThat(Images.near(img.getRGB(dx, dy), TL)).as("superior esquerdo").isTrue();
        assertThat(Images.near(img.getRGB(w - 1 - dx, dy), TR)).as("superior direito").isTrue();
        assertThat(Images.near(img.getRGB(dx, h - 1 - dy), BL)).as("inferior esquerdo").isTrue();
        assertThat(Images.near(img.getRGB(w - 1 - dx, h - 1 - dy), BR)).as("inferior direito").isTrue();
    }

    private static boolean isReddish(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r > 150 && g < 110 && b < 110;
    }

    private static void assertReason(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).reason()).isEqualTo(reason);
    }

    /**
     * Inverso de cada orientação: a imagem que a câmera gravaria para que, aplicada a orientação, ela apareça em pé.
     * (2, 3, 4, 5 e 7 são o próprio inverso; 6 e 8 são inversos um do outro.)
     */
    private static BufferedImage inverse(BufferedImage upright, int orientation) {
        int inv = switch (orientation) {
            case 6 -> 8;
            case 8 -> 6;
            default -> orientation;
        };
        return com.socialflux.media.image.Orientation.apply(upright, inv);
    }

    /** PNG com só IHDR (dimensões declaradas) e IEND, com CRCs corretos. */
    private static byte[] pngHeaderOnly(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8;   // profundidade
        ihdr[9] = 2;   // RGB
        chunk(out, "IHDR", ihdr);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.writeBytes(len);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.writeBytes(c);
    }

    private static void putInt(byte[] b, int pos, int v) {
        b[pos] = (byte) (v >>> 24);
        b[pos + 1] = (byte) (v >>> 16);
        b[pos + 2] = (byte) (v >>> 8);
        b[pos + 3] = (byte) v;
    }
}
