package com.socialflux.media.image;

import com.socialflux.media.MediaException;
import com.socialflux.media.MediaException.Reason;
import com.socialflux.media.MediaFormat;
import com.socialflux.media.MediaProperties;
import com.socialflux.media.ProcessedMedia;
import com.socialflux.media.ProcessingLimiter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Imagens estáticas PNG, JPEG e WebP (D9). O arquivo enviado NUNCA é guardado: a imagem é decodificada e um arquivo novo é
 * gerado a partir dos pixels, sem nenhum metadado. Passos:
 * <ol>
 *   <li>tamanho em bytes e bytes mágicos (ignora nome e {@code Content-Type} do cliente);</li>
 *   <li>PNG/WebP animados são recusados (animação só por GIF);</li>
 *   <li>lê só o cabeçalho e recusa {@code largura × altura} acima do limite (image bomb) ANTES de decodificar;</li>
 *   <li>lê a orientação EXIF do original, decodifica (JPEG CMYK/YCCK vira RGB pelo TwelveMonkeys), reduz e aplica a
 *   orientação nos pixels;</li>
 *   <li>re-codifica: JPEG (qualidade 0,85) se opaca, PNG se houver transparência.</li>
 * </ol>
 * Tudo dentro do {@link ProcessingLimiter} (limite de processamentos simultâneos).
 */
public class ImageProcessor {

    private static final float JPEG_QUALITY = 0.85f;

    private final MediaProperties.Image limits;
    private final ProcessingLimiter limiter;

    public ImageProcessor(MediaProperties.Image limits, ProcessingLimiter limiter) {
        this.limits = limits;
        this.limiter = limiter;
        System.setProperty("java.awt.headless", "true");
        ImageIO.setUseCache(false);       // nada de arquivos temporários em disco
        ImageIO.scanForPlugins();         // registra os leitores do TwelveMonkeys também em jars executáveis
    }

    /** Postagens: mantém a proporção e reduz até o lado maior ≤ {@code max-side} (nunca amplia). */
    public ProcessedMedia fit(byte[] data) {
        return limiter.run(() -> {
            Decoded decoded = decode(data);
            BufferedImage scaled = Orientation.apply(downscale(decoded.image(), limits.maxSide()), decoded.orientation());
            return encode(scaled, decoded.transparent());
        });
    }

    /** Avatares: recorte quadrado central em {@code size}×{@code size}, sempre JPEG (transparência vira fundo branco). */
    public ProcessedMedia squareJpeg(byte[] data, int size) {
        return limiter.run(() -> {
            Decoded decoded = decode(data);
            BufferedImage oriented = Orientation.apply(decoded.image(), decoded.orientation());
            return encode(squareThumbnail(oriented, size), false);
        });
    }

    private record Decoded(BufferedImage image, int orientation, boolean transparent) {
    }

    private Decoded decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new MediaException(Reason.EMPTY, "Arquivo de imagem vazio");
        }
        if (data.length > limits.maxBytes().toBytes()) {
            throw new MediaException(Reason.TOO_LARGE, "A imagem excede o tamanho máximo permitido");
        }
        MediaFormat format = MediaFormat.detect(data);
        if (format != MediaFormat.PNG && format != MediaFormat.JPEG && format != MediaFormat.WEBP) {
            throw invalid();
        }
        if (ImageInspector.isAnimated(format, data)) {
            throw new MediaException(Reason.ANIMATED, "Imagens animadas não são aceitas: para animações, envie um GIF");
        }
        int orientation = ImageInspector.orientation(format, data);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = readerFor(input);
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > limits.maxPixels()) {
                    throw new MediaException(Reason.DIMENSIONS_TOO_LARGE, "A imagem tem dimensões grandes demais");
                }
                BufferedImage source = reader.read(0);
                boolean transparent = hasTransparency(source);
                return new Decoded(normalize(source, transparent), orientation, transparent);
            } finally {
                reader.dispose();
            }
        } catch (MediaException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
    }

    private static ImageReader readerFor(ImageInputStream input) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw invalid();
        }
        return readers.next();
    }

    /** Há algum pixel não totalmente opaco? (Um PNG com canal alfa mas todo opaco vira JPEG, que é bem menor.) */
    private static boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }
        int w = image.getWidth();
        int[] row = new int[w];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int argb : row) {
                if ((argb >>> 24) != 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Copia para RGB/ARGB padrão (converte espaços de cor como CMYK e paletas). */
    private static BufferedImage normalize(BufferedImage source, boolean transparent) {
        int type = transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (source.getType() == type) {
            return source;
        }
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D g = out.createGraphics();
        try {
            if (!transparent) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, out.getWidth(), out.getHeight());
            }
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Reduz em passos de metade (melhor qualidade que um salto só) até o lado maior caber em {@code maxSide}. */
    static BufferedImage downscale(BufferedImage image, int maxSide) {
        int w = image.getWidth();
        int h = image.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxSide) {
            return image;
        }
        double scale = (double) maxSide / longest;
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage current = image;
        while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        if (current.getWidth() != targetW || current.getHeight() != targetH) {
            current = resize(current, targetW, targetH);
        }
        return current;
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, src.getType());
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage squareThumbnail(BufferedImage source, int size) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, size, size);
            g.drawImage(source, 0, 0, size, size, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static ProcessedMedia encode(BufferedImage image, boolean transparent) {
        try {
            byte[] bytes = transparent ? writePng(image) : writeJpeg(image);
            return new ProcessedMedia(bytes, transparent ? ProcessedMedia.PNG : ProcessedMedia.JPEG,
                    transparent ? "png" : "jpg", image.getWidth(), image.getHeight(), ProcessedMedia.Kind.IMAGE);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao codificar a imagem", e);
        }
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = writer("jpeg");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        ImageWriter writer = writer("png");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /** Prefere o codificador padrão do JDK (o TwelveMonkeys entra só para LER). */
    private static ImageWriter writer(String format) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        ImageWriter fallback = null;
        while (writers.hasNext()) {
            ImageWriter candidate = writers.next();
            if (candidate.getClass().getName().startsWith("com.sun.imageio")) {
                if (fallback != null) {
                    fallback.dispose();
                }
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            } else {
                candidate.dispose();
            }
        }
        if (fallback == null) {
            throw new IOException("Sem codificador " + format);
        }
        return fallback;
    }

    private static MediaException invalid() {
        return new MediaException(Reason.INVALID, "Formato de imagem não suportado (use PNG, JPEG ou WebP)");
    }
}
