package com.socialflux.media;

/**
 * Arquivo recusado (ou servidor sem vaga para processá-lo). A biblioteca não conhece HTTP nem o formato de erro de cada
 * serviço: cada serviço traduz o {@link Reason} para o seu código público ({@code imagem_invalida}, {@code invalid_image}...).
 */
public class MediaException extends RuntimeException {

    public enum Reason {
        /** Arquivo vazio. */
        EMPTY,
        /** Arquivo maior que o limite em bytes. */
        TOO_LARGE,
        /** Dimensões (ou pixels decodificados) acima do limite. */
        DIMENSIONS_TOO_LARGE,
        /** Não é PNG, JPEG, WebP (nem GIF, quando aceito), ou não pôde ser decodificado. */
        INVALID,
        /** PNG ou WebP animado: animação só por GIF. */
        ANIMATED,
        /** GIF malformado ou fora dos limites de quadros. */
        GIF_INVALID,
        /** Sem vaga para processar no tempo de espera (limite de processamentos simultâneos). */
        BUSY
    }

    private final Reason reason;

    public MediaException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
