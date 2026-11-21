package com.socialflux.media.image;

import java.awt.image.BufferedImage;

/**
 * Aplica a orientação EXIF (1 a 8) nos PIXELS, para que a imagem gravada (sem EXIF) apareça na posição certa.
 *
 * <pre>
 * 1 normal                      nada
 * 2 espelhada na horizontal     (x, y) → (w-1-x, y)
 * 3 girada 180°                 (x, y) → (w-1-x, h-1-y)
 * 4 espelhada na vertical       (x, y) → (x, h-1-y)
 * 5 transposta                  (x, y) → (y, x)              largura/altura trocadas
 * 6 girada 90° horário          (x, y) → (h-1-y, x)          largura/altura trocadas
 * 7 transversa                  (x, y) → (h-1-y, w-1-x)      largura/altura trocadas
 * 8 girada 90° anti-horário     (x, y) → (y, w-1-x)          largura/altura trocadas
 * </pre>
 */
public final class Orientation {

    private Orientation() {
    }

    /** As orientações 5 a 8 trocam largura e altura. */
    public static boolean swapsDimensions(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    /** Devolve a imagem na orientação normal (a própria imagem quando {@code orientation} é 1 ou inválida). */
    public static BufferedImage apply(BufferedImage src, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = swapsDimensions(orientation);
        int outW = swap ? h : w;
        int outH = swap ? w : h;
        int[] in = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx;
                int dy;
                switch (orientation) {
                    case 2 -> { dx = w - 1 - x; dy = y; }
                    case 3 -> { dx = w - 1 - x; dy = h - 1 - y; }
                    case 4 -> { dx = x; dy = h - 1 - y; }
                    case 5 -> { dx = y; dy = x; }
                    case 6 -> { dx = h - 1 - y; dy = x; }
                    case 7 -> { dx = h - 1 - y; dy = w - 1 - x; }
                    default -> { dx = y; dy = w - 1 - x; }   // 8
                }
                out[dy * outW + dx] = in[y * w + x];
            }
        }
        BufferedImage result = new BufferedImage(outW, outH, src.getType());
        result.setRGB(0, 0, outW, outH, out, 0, outW);
        return result;
    }
}
