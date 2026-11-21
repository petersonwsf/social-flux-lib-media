package com.socialflux.media.support;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Geração e inspeção de imagens para os testes. */
public final class Images {

    public static final Color TL = Color.RED;
    public static final Color TR = Color.GREEN;
    public static final Color BL = Color.BLUE;
    public static final Color BR = Color.YELLOW;

    private Images() {
    }

    /** Imagem com uma cor diferente em cada quadrante (a orientação "correta" nos testes). */
    public static BufferedImage quadrants(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(TL);
        g.fillRect(0, 0, w / 2, h / 2);
        g.setColor(TR);
        g.fillRect(w / 2, 0, w - w / 2, h / 2);
        g.setColor(BL);
        g.fillRect(0, h / 2, w / 2, h - h / 2);
        g.setColor(BR);
        g.fillRect(w / 2, h / 2, w - w / 2, h - h / 2);
        g.dispose();
        return img;
    }

    public static byte[] jpeg(BufferedImage img) {
        return write(img, "jpeg");
    }

    public static byte[] png(BufferedImage img) {
        return write(img, "png");
    }

    public static byte[] write(BufferedImage img, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(img, format, out)) {
                throw new IllegalStateException("sem codificador " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BufferedImage read(byte[] data) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) {
                throw new IllegalStateException("não decodificou");
            }
            return img;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] fixture(String name) {
        try (InputStream in = Images.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture ausente: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Insere logo depois do SOI um segmento APP1/EXIF com a orientação dada (TIFF big-endian, IFD0 com a tag 0x0112 e uma
     * segunda entrada ASCII "GPS-SECRETO" para conferir que nenhum metadado sobrevive).
     */
    public static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] secret = "GPS-SECRETO".getBytes(StandardCharsets.US_ASCII);   // 11 bytes + NUL = 12
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.writeBytes(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8});
        tiff.writeBytes(new byte[]{0, 2});                                     // 2 entradas
        tiff.writeBytes(new byte[]{0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0});
        int valueOffset = 8 + 2 + 12 * 2 + 4;
        tiff.writeBytes(new byte[]{0x01, 0x0E, 0, 2, 0, 0, 0, 12, 0, 0, 0, (byte) valueOffset});  // ImageDescription
        tiff.writeBytes(new byte[]{0, 0, 0, 0});                               // sem próximo IFD
        tiff.writeBytes(secret);
        tiff.write(0);
        byte[] body = tiff.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        int length = 2 + 6 + body.length;
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(body);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** A cor no ponto está próxima da esperada (JPEG tem perdas)? */
    public static boolean near(int argb, Color expected) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return Math.abs(r - expected.getRed()) < 60 && Math.abs(g - expected.getGreen()) < 60
                && Math.abs(b - expected.getBlue()) < 60;
    }

    public static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
