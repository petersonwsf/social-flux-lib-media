package com.socialflux.media.gif;

import com.socialflux.media.MediaException;
import com.socialflux.media.MediaException.Reason;
import com.socialflux.media.MediaFormat;
import com.socialflux.media.MediaProperties;
import com.socialflux.media.ProcessedMedia;
import com.socialflux.media.ProcessingLimiter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Valida e REGRAVA um GIF (D16) sem decodificar os pixels: re-codificar para JPEG mataria a animação. O arquivo é lido por
 * blocos (cabeçalho, tela lógica, tabelas de cores, quadros e extensões) e só o necessário é copiado para um arquivo novo:
 * <ul>
 *   <li>mantidos: quadros, extensões de controle (tempo e transparência) e o laço de repetição
 *   ({@code NETSCAPE2.0}/{@code ANIMEXTS1.0});</li>
 *   <li>descartados: comentários, texto, extensões de aplicação desconhecidas e QUALQUER byte depois do primeiro fim
 *   ({@code 0x3B}), o que neutraliza arquivos poliglotas;</li>
 *   <li>recusados: estrutura malformada ou truncada, dimensões acima do limite, quadros demais e pixels decodificados
 *   (largura × altura × quadros) acima do teto — a "bomba de descompressão" é contida por esse teto.</li>
 * </ul>
 */
public class GifSanitizer {

    private static final int TRAILER = 0x3B;
    private static final int EXTENSION = 0x21;
    private static final int IMAGE = 0x2C;
    private static final int GRAPHIC_CONTROL = 0xF9;
    private static final int APPLICATION = 0xFF;

    private final MediaProperties.Gif limits;
    private final ProcessingLimiter limiter;

    public GifSanitizer(MediaProperties.Gif limits, ProcessingLimiter limiter) {
        this.limits = limits;
        this.limiter = limiter;
    }

    public ProcessedMedia sanitize(byte[] data) {
        if (data == null || data.length == 0) {
            throw new MediaException(Reason.EMPTY, "Arquivo de imagem vazio");
        }
        if (data.length > limits.maxBytes().toBytes()) {
            throw new MediaException(Reason.TOO_LARGE, "O GIF excede o tamanho máximo permitido");
        }
        if (MediaFormat.detect(data) != MediaFormat.GIF) {
            throw invalid("O arquivo não é um GIF válido");
        }
        return limiter.run(() -> rewrite(data));
    }

    private ProcessedMedia rewrite(byte[] data) {
        Reader in = new Reader(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);

        out.writeBytes(in.take(6));                      // GIF87a / GIF89a
        byte[] screen = in.take(7);                      // Logical Screen Descriptor
        int width = u16le(screen, 0);
        int height = u16le(screen, 2);
        if (width == 0 || height == 0) {
            throw invalid("GIF sem dimensões");
        }
        if (width > limits.maxSide() || height > limits.maxSide()) {
            throw new MediaException(Reason.DIMENSIONS_TOO_LARGE, "O GIF tem dimensões grandes demais");
        }
        out.writeBytes(screen);
        int flags = screen[4] & 0xFF;
        if ((flags & 0x80) != 0) {                       // tabela de cores global
            out.writeBytes(in.take(colorTableSize(flags)));
        }

        int frames = 0;
        byte[] pendingControl = null;                    // controle gráfico só vale para o próximo quadro
        while (true) {
            int introducer = in.next();
            if (introducer == TRAILER) {
                break;
            }
            if (introducer == EXTENSION) {
                int label = in.next();
                if (label == GRAPHIC_CONTROL) {
                    pendingControl = graphicControl(in);
                } else if (label == APPLICATION) {
                    byte[] app = application(in);
                    if (app != null) {
                        out.writeBytes(app);
                    }
                } else {
                    skipSubBlocks(in);                   // comentário (0xFE), texto (0x01) e desconhecidas: descartadas
                }
            } else if (introducer == IMAGE) {
                frames++;
                if (frames > limits.maxFrames()) {
                    throw invalid("O GIF tem quadros demais");
                }
                if ((long) width * height * frames > limits.maxTotalPixels()) {
                    throw new MediaException(Reason.DIMENSIONS_TOO_LARGE, "O GIF é grande demais depois de descompactado");
                }
                if (pendingControl != null) {
                    out.writeBytes(pendingControl);
                    pendingControl = null;
                }
                out.write(IMAGE);
                out.writeBytes(imageBlock(in));
            } else {
                throw invalid("GIF malformado");
            }
        }
        if (frames == 0) {
            throw invalid("GIF sem nenhum quadro");
        }
        out.write(TRAILER);                              // termina no primeiro fim: o que vier depois é descartado
        byte[] result = out.toByteArray();
        if (result.length > limits.maxBytes().toBytes()) {
            throw new MediaException(Reason.TOO_LARGE, "O GIF excede o tamanho máximo permitido");
        }
        return new ProcessedMedia(result, ProcessedMedia.GIF_TYPE, "gif", width, height, ProcessedMedia.Kind.GIF);
    }

    private static byte[] graphicControl(Reader in) {
        int size = in.next();
        if (size != 4) {
            throw invalid("GIF malformado");
        }
        byte[] body = in.take(4);
        if (in.next() != 0) {
            throw invalid("GIF malformado");
        }
        ByteArrayOutputStream block = new ByteArrayOutputStream(8);
        block.write(EXTENSION);
        block.write(GRAPHIC_CONTROL);
        block.write(4);
        block.writeBytes(body);
        block.write(0);
        return block.toByteArray();
    }

    /** Mantém só o bloco de laço (NETSCAPE2.0 / ANIMEXTS1.0); descarta as demais aplicações. */
    private static byte[] application(Reader in) {
        int size = in.next();
        if (size != 11) {
            in.skip(size);
            skipSubBlocks(in);
            return null;
        }
        byte[] id = in.take(11);
        String name = new String(id, StandardCharsets.US_ASCII);
        if (!"NETSCAPE2.0".equals(name) && !"ANIMEXTS1.0".equals(name)) {
            skipSubBlocks(in);
            return null;
        }
        ByteArrayOutputStream block = new ByteArrayOutputStream(19);
        block.write(EXTENSION);
        block.write(APPLICATION);
        block.write(11);
        block.writeBytes(id);
        block.writeBytes(subBlocks(in));
        return block.toByteArray();
    }

    /** Descritor do quadro (9 bytes), tabela local opcional, tamanho mínimo do código LZW e os sub-blocos de dados. */
    private byte[] imageBlock(Reader in) {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        byte[] descriptor = in.take(9);
        int frameW = u16le(descriptor, 4);
        int frameH = u16le(descriptor, 6);
        if (frameW == 0 || frameH == 0) {
            throw invalid("GIF com quadro vazio");
        }
        if (frameW > limits.maxSide() || frameH > limits.maxSide()) {
            throw new MediaException(Reason.DIMENSIONS_TOO_LARGE, "O GIF tem dimensões grandes demais");
        }
        block.writeBytes(descriptor);
        int flags = descriptor[8] & 0xFF;
        if ((flags & 0x80) != 0) {
            block.writeBytes(in.take(colorTableSize(flags)));
        }
        int minCodeSize = in.next();
        if (minCodeSize < 2 || minCodeSize > 8) {
            throw invalid("GIF malformado");
        }
        block.write(minCodeSize);
        block.writeBytes(subBlocks(in));
        return block.toByteArray();
    }

    /** Copia uma sequência de sub-blocos até o terminador {@code 0x00} (incluído). */
    private static byte[] subBlocks(Reader in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int size = in.next();
            out.write(size);
            if (size == 0) {
                return out.toByteArray();
            }
            out.writeBytes(in.take(size));
        }
    }

    private static void skipSubBlocks(Reader in) {
        while (true) {
            int size = in.next();
            if (size == 0) {
                return;
            }
            in.skip(size);
        }
    }

    private static int colorTableSize(int flags) {
        return 3 * (1 << ((flags & 0x07) + 1));
    }

    private static int u16le(byte[] d, int pos) {
        return (d[pos] & 0xFF) | ((d[pos + 1] & 0xFF) << 8);
    }

    private static MediaException invalid(String message) {
        return new MediaException(Reason.GIF_INVALID, message);
    }

    /** Leitura sequencial que recusa (como GIF malformado) qualquer tentativa de passar do fim do arquivo. */
    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        int next() {
            if (pos >= data.length) {
                throw invalid("GIF truncado");
            }
            return data[pos++] & 0xFF;
        }

        byte[] take(int n) {
            if (n < 0 || n > data.length - pos) {
                throw invalid("GIF truncado");
            }
            byte[] out = new byte[n];
            System.arraycopy(data, pos, out, 0, n);
            pos += n;
            return out;
        }

        void skip(int n) {
            if (n < 0 || n > data.length - pos) {
                throw invalid("GIF truncado");
            }
            pos += n;
        }
    }
}
