package com.socialflux.media;

import com.socialflux.media.MediaException.Reason;
import com.socialflux.media.gif.GifSanitizer;
import com.socialflux.media.support.Images;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GifSanitizerTest {

    private final GifSanitizer sanitizer = sanitizer(DataSize.ofMegabytes(8), 1280, 300, 256_000_000L);

    static GifSanitizer sanitizer(DataSize maxBytes, int maxSide, int maxFrames, long maxTotalPixels) {
        return new GifSanitizer(new MediaProperties.Gif(maxBytes, maxSide, maxFrames, maxTotalPixels),
                new ProcessingLimiter(2, Duration.ofSeconds(5)));
    }

    @Test
    void keepsValidSingleFrameGif() {
        byte[] gif = new GifBuilder(1, 1).frame().build();
        ProcessedMedia result = sanitizer.sanitize(gif);
        assertThat(result.bytes()).isEqualTo(gif);
        assertThat(result.contentType()).isEqualTo("image/gif");
        assertThat(result.extension()).isEqualTo("gif");
        assertThat(result.kind()).isEqualTo(ProcessedMedia.Kind.GIF);
        assertThat(result.width()).isEqualTo(1);
    }

    @Test
    void keepsAnimationFramesAndLoop() throws Exception {
        byte[] gif = animatedWithJdk(3);
        ProcessedMedia result = sanitizer.sanitize(gif);
        assertThat(frames(result.bytes())).isEqualTo(3);
        assertThat(Images.contains(result.bytes(), "NETSCAPE2.0")).isTrue();
        assertThat(result.width()).isEqualTo(40);
        assertThat(result.height()).isEqualTo(30);
    }

    @Test
    void dropsCommentsUnknownExtensionsAndForeignApplications() {
        byte[] gif = new GifBuilder(2, 2).comment("SEGREDO").unknownExtension(0x99).application("XMP DataXMP", "dados")
                .loop().frame().build();
        byte[] out = sanitizer.sanitize(gif).bytes();
        assertThat(Images.contains(out, "SEGREDO")).isFalse();
        assertThat(Images.contains(out, "XMP DataXMP")).isFalse();
        assertThat(Images.contains(out, "NETSCAPE2.0")).isTrue();
        assertThat(out[out.length - 1]).isEqualTo((byte) 0x3B);
    }

    @Test
    void dropsBytesAfterTrailer() {
        byte[] gif = new GifBuilder(1, 1).frame().build();
        byte[] polyglot = concat(gif, "<script>alert(1)</script>".getBytes());
        byte[] out = sanitizer.sanitize(polyglot).bytes();
        assertThat(out).isEqualTo(gif);
    }

    @Test
    void refusesTruncatedGif() {
        byte[] gif = new GifBuilder(1, 1).frame().frame().build();
        assertReason(() -> sanitizer.sanitize(Arrays.copyOf(gif, gif.length - 1)), Reason.GIF_INVALID);    // sem trailer
        assertReason(() -> sanitizer.sanitize(Arrays.copyOf(gif, 20)), Reason.GIF_INVALID);
    }

    @Test
    void refusesTooManyFrames() {
        GifSanitizer strict = sanitizer(DataSize.ofMegabytes(8), 1280, 3, 256_000_000L);
        GifBuilder builder = new GifBuilder(1, 1);
        for (int i = 0; i < 4; i++) {
            builder.frame();
        }
        assertReason(() -> strict.sanitize(builder.build()), Reason.GIF_INVALID);
    }

    @Test
    void refusesFrameBombByDecodedPixels() {
        GifSanitizer strict = sanitizer(DataSize.ofMegabytes(8), 1280, 300, 1_000_000L);
        GifBuilder builder = new GifBuilder(1000, 1000);   // quadros minúsculos, mas tela de 1 MP cada
        builder.frame().frame();
        assertReason(() -> strict.sanitize(builder.build()), Reason.DIMENSIONS_TOO_LARGE);
    }

    @Test
    void refusesInconsistentColorTable() {
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, (byte) 0xF7, 0, 0, 1, 2, 3};   // promete 256 cores
        assertReason(() -> sanitizer.sanitize(gif), Reason.GIF_INVALID);
    }

    @Test
    void refusesInvalidStructure() {
        byte[] gif = new GifBuilder(1, 1).frame().build();
        byte[] broken = gif.clone();
        broken[19] = 0x55;   // introdutor desconhecido no lugar do primeiro bloco (6 + 7 + tabela de 6 bytes)
        assertReason(() -> sanitizer.sanitize(broken), Reason.GIF_INVALID);
        assertReason(() -> sanitizer.sanitize(new GifBuilder(1, 1).build()), Reason.GIF_INVALID);   // sem quadros
    }

    @Test
    void refusesPngRenamedAsGif() {
        byte[] png = Images.png(Images.quadrants(4, 4));
        assertReason(() -> sanitizer.sanitize(png), Reason.GIF_INVALID);
    }

    @Test
    void refusesZeroOrOversizedDimensions() {
        assertReason(() -> sanitizer.sanitize(new GifBuilder(0, 0).frame().build()), Reason.GIF_INVALID);
        assertReason(() -> sanitizer.sanitize(new GifBuilder(2000, 10).frame().build()), Reason.DIMENSIONS_TOO_LARGE);
    }

    @Test
    void refusesFileOverTheByteLimit() {
        GifSanitizer small = sanitizer(DataSize.ofBytes(30), 1280, 300, 256_000_000L);
        assertReason(() -> small.sanitize(new GifBuilder(1, 1).frame().frame().build()), Reason.TOO_LARGE);
    }

    // ------------------------------------------------------------------ auxiliares

    private static void assertReason(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).reason()).isEqualTo(reason);
    }

    private static int frames(byte[] gif) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(in);
            int n = reader.getNumImages(true);
            for (int i = 0; i < n; i++) {
                reader.read(i);   // decodifica de verdade cada quadro
            }
            reader.dispose();
            return n;
        }
    }

    /** GIF animado gravado pelo codificador do JDK, com laço infinito (NETSCAPE2.0). */
    private static byte[] animatedWithJdk(int frameCount) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frameCount; i++) {
                BufferedImage frame = Images.quadrants(40, 30);
                ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(frame);
                IIOMetadata metadata = writer.getDefaultImageMetadata(type, null);
                String format = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
                IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", "10");
                gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                if (i == 0) {
                    IIOMetadataNode apps = new IIOMetadataNode("ApplicationExtensions");
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[]{1, 0, 0});
                    apps.appendChild(app);
                    root.appendChild(apps);
                }
                metadata.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(frame, null, metadata), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Monta GIFs à mão: tela lógica WxH com tabela global de 2 cores e quadros 1×1 válidos. */
    static final class GifBuilder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        GifBuilder(int width, int height) {
            out.writeBytes(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
            out.writeBytes(new byte[]{(byte) width, (byte) (width >> 8), (byte) height, (byte) (height >> 8),
                    (byte) 0x80, 0, 0});
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0});
        }

        GifBuilder frame() {
            out.writeBytes(new byte[]{0x21, (byte) 0xF9, 4, 0, 10, 0, 0, 0});                 // controle gráfico
            out.writeBytes(new byte[]{0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0});                      // descritor 1×1
            out.writeBytes(new byte[]{2, 2, 0x44, 1, 0});                                     // LZW mínimo 2 + dados
            return this;
        }

        GifBuilder comment(String text) {
            out.writeBytes(new byte[]{0x21, (byte) 0xFE, (byte) text.length()});
            out.writeBytes(text.getBytes());
            out.write(0);
            return this;
        }

        GifBuilder unknownExtension(int label) {
            out.writeBytes(new byte[]{0x21, (byte) label, 3, 1, 2, 3, 0});
            return this;
        }

        GifBuilder application(String id11, String data) {
            out.writeBytes(new byte[]{0x21, (byte) 0xFF, 11});
            out.writeBytes(id11.getBytes());
            out.write(data.length());
            out.writeBytes(data.getBytes());
            out.write(0);
            return this;
        }

        GifBuilder loop() {
            out.writeBytes(new byte[]{0x21, (byte) 0xFF, 11});
            out.writeBytes("NETSCAPE2.0".getBytes());
            out.writeBytes(new byte[]{3, 1, 0, 0, 0});
            return this;
        }

        byte[] build() {
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            copy.writeBytes(out.toByteArray());
            copy.write(0x3B);
            return copy.toByteArray();
        }
    }
}
