package com.socialflux.media.image;

import com.socialflux.media.MediaFormat;

/**
 * Leitura binária, sem decodificar pixels, do que o Java não informa sozinho: a orientação EXIF (tag 0x0112) e se um PNG ou
 * WebP é animado. Toda leitura é limitada ao tamanho do arquivo; dado malformado é tratado como "sem orientação" / "não
 * animado" (a decodificação seguinte recusa o que não for imagem válida).
 */
public final class ImageInspector {

    private static final int ORIENTATION_TAG = 0x0112;
    private static final int MAX_CHUNKS = 10_000;

    private ImageInspector() {
    }

    /** Orientação EXIF de 1 a 8; 1 quando ausente, ilegível ou inválida. */
    public static int orientation(MediaFormat format, byte[] data) {
        try {
            int value = switch (format) {
                case JPEG -> jpegOrientation(data);
                case PNG -> pngOrientation(data);
                case WEBP -> webpOrientation(data);
                default -> 1;
            };
            return value >= 1 && value <= 8 ? value : 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** PNG com {@code acTL} (APNG) ou WebP com a flag/blocos de animação. */
    public static boolean isAnimated(MediaFormat format, byte[] data) {
        try {
            return switch (format) {
                case PNG -> pngHasChunkBeforeData(data, "acTL");
                case WEBP -> webpAnimated(data);
                default -> false;
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- JPEG

    private static int jpegOrientation(byte[] d) {
        int pos = 2;
        while (pos + 4 <= d.length) {
            if ((d[pos] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = d[pos + 1] & 0xFF;
            if (marker == 0xFF) {            // bytes de preenchimento
                pos++;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {   // fim da imagem ou início dos dados: não há mais cabeçalhos
                return 1;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;
                continue;
            }
            int length = u16be(d, pos + 2);
            if (length < 2 || pos + 2 + length > d.length) {
                return 1;
            }
            int segment = pos + 4;
            if (marker == 0xE1 && length >= 8 && isExifHeader(d, segment)) {
                return tiffOrientation(d, segment + 6, pos + 2 + length);
            }
            pos += 2 + length;
        }
        return 1;
    }

    // ---------------------------------------------------------------- PNG

    private static int pngOrientation(byte[] d) {
        int pos = 8;
        for (int i = 0; i < MAX_CHUNKS && pos + 12 <= d.length; i++) {
            long length = u32be(d, pos);
            if (length > d.length - pos - 12) {
                return 1;
            }
            String type = fourCc(d, pos + 4);
            int start = pos + 8;
            int end = start + (int) length;
            if ("eXIf".equals(type)) {
                int tiff = isExifHeader(d, start) ? start + 6 : start;
                return tiffOrientation(d, tiff, end);
            }
            if ("IEND".equals(type)) {
                return 1;
            }
            pos = end + 4;
        }
        return 1;
    }

    private static boolean pngHasChunkBeforeData(byte[] d, String wanted) {
        int pos = 8;
        for (int i = 0; i < MAX_CHUNKS && pos + 12 <= d.length; i++) {
            long length = u32be(d, pos);
            if (length > d.length - pos - 12) {
                return false;
            }
            String type = fourCc(d, pos + 4);
            if (wanted.equals(type)) {
                return true;
            }
            if ("IDAT".equals(type) || "IEND".equals(type)) {
                return false;
            }
            pos += 12 + (int) length;
        }
        return false;
    }

    // ---------------------------------------------------------------- WebP

    private static int webpOrientation(byte[] d) {
        int[] exif = webpChunk(d, "EXIF");
        if (exif == null) {
            return 1;
        }
        int tiff = isExifHeader(d, exif[0]) ? exif[0] + 6 : exif[0];
        return tiffOrientation(d, tiff, exif[1]);
    }

    private static boolean webpAnimated(byte[] d) {
        int[] vp8x = webpChunk(d, "VP8X");
        if (vp8x != null && vp8x[1] > vp8x[0] && (d[vp8x[0]] & 0x02) != 0) {
            return true;
        }
        return webpChunk(d, "ANIM") != null || webpChunk(d, "ANMF") != null;
    }

    /** Início e fim (exclusivo) dos dados do primeiro bloco {@code fourCc}, ou {@code null}. */
    private static int[] webpChunk(byte[] d, String wanted) {
        int pos = 12;
        for (int i = 0; i < MAX_CHUNKS && pos + 8 <= d.length; i++) {
            String type = fourCc(d, pos);
            long size = u32le(d, pos + 4);
            if (size > d.length - pos - 8) {
                return null;
            }
            int start = pos + 8;
            int end = start + (int) size;
            if (wanted.equals(type)) {
                return new int[]{start, end};
            }
            pos = end + (int) (size & 1);   // blocos alinhados em 2 bytes
        }
        return null;
    }

    // ---------------------------------------------------------------- TIFF (conteúdo do EXIF)

    private static int tiffOrientation(byte[] d, int base, int end) {
        if (base + 8 > end || end > d.length) {
            return 1;
        }
        boolean little;
        if (d[base] == 'I' && d[base + 1] == 'I') {
            little = true;
        } else if (d[base] == 'M' && d[base + 1] == 'M') {
            little = false;
        } else {
            return 1;
        }
        if (u16(d, base + 2, little) != 42) {
            return 1;
        }
        long ifd = u32(d, base + 4, little);
        if (ifd < 8 || ifd > end - base - 2) {
            return 1;
        }
        int pos = base + (int) ifd;
        int count = u16(d, pos, little);
        pos += 2;
        for (int i = 0; i < count && pos + 12 <= end; i++, pos += 12) {
            if (u16(d, pos, little) == ORIENTATION_TAG) {
                int type = u16(d, pos + 2, little);
                if (type == 3) {          // SHORT: valor nos 2 primeiros bytes do campo
                    return u16(d, pos + 8, little);
                }
                if (type == 4) {          // LONG (raro, mas visto em alguns arquivos)
                    return (int) u32(d, pos + 8, little);
                }
                return 1;
            }
        }
        return 1;
    }

    private static boolean isExifHeader(byte[] d, int pos) {
        return pos + 6 <= d.length && d[pos] == 'E' && d[pos + 1] == 'x' && d[pos + 2] == 'i' && d[pos + 3] == 'f'
                && d[pos + 4] == 0 && d[pos + 5] == 0;
    }

    // ---------------------------------------------------------------- leitura de inteiros

    private static String fourCc(byte[] d, int pos) {
        return new String(d, pos, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static int u16be(byte[] d, int pos) {
        return ((d[pos] & 0xFF) << 8) | (d[pos + 1] & 0xFF);
    }

    private static long u32be(byte[] d, int pos) {
        return ((long) (d[pos] & 0xFF) << 24) | ((d[pos + 1] & 0xFF) << 16) | ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
    }

    private static long u32le(byte[] d, int pos) {
        return ((long) (d[pos + 3] & 0xFF) << 24) | ((d[pos + 2] & 0xFF) << 16) | ((d[pos + 1] & 0xFF) << 8) | (d[pos] & 0xFF);
    }

    private static int u16(byte[] d, int pos, boolean little) {
        return little ? ((d[pos + 1] & 0xFF) << 8) | (d[pos] & 0xFF) : u16be(d, pos);
    }

    private static long u32(byte[] d, int pos, boolean little) {
        return little ? u32le(d, pos) : u32be(d, pos);
    }
}
