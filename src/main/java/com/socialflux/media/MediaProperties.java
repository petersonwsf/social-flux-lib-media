package com.socialflux.media;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuração da mídia ({@code socialflux.media.*}); cada serviço aponta as chaves para as suas variáveis de ambiente.
 *
 * @param storage                 {@code s3} (RustFS/MinIO/S3; padrão) ou {@code memory} (testes, sem Docker)
 * @param maxConcurrentProcessing quantas imagens podem ser decodificadas ao mesmo tempo (uma de 16 MP ocupa ~64 MB)
 * @param acquireTimeout          quanto esperar por uma vaga antes de recusar com {@link MediaException.Reason#BUSY}
 */
@Validated
@ConfigurationProperties(prefix = "socialflux.media")
public record MediaProperties(
        @DefaultValue("s3") @NotNull Storage storage,
        @Valid @DefaultValue S3 s3,
        @Valid @DefaultValue Image image,
        @Valid @DefaultValue Gif gif,
        @DefaultValue("4") @Min(1) int maxConcurrentProcessing,
        @DefaultValue("PT5S") @NotNull Duration acquireTimeout) {

    public enum Storage { S3, MEMORY }

    /**
     * Acesso ao armazenamento S3 compatível. {@code endpoint} e as chaves são obrigatórios quando {@code storage=s3}
     * (conferido ao criar o cliente). Endereçamento por caminho (path-style) é sempre usado.
     */
    public record S3(String endpoint, @DefaultValue("us-east-1") @NotBlank String region, String accessKey,
                     String secretKey) {
    }

    /** Imagens estáticas (D9): limites de entrada e lado maior da saída. */
    public record Image(
            @DefaultValue("5MB") @NotNull DataSize maxBytes,
            @DefaultValue("16000000") @Min(1) long maxPixels,
            @DefaultValue("1600") @Min(16) int maxSide) {
    }

    /** GIFs (D16): limites de arquivo, dimensão, quadros e pixels decodificados (largura × altura × quadros). */
    public record Gif(
            @DefaultValue("8MB") @NotNull DataSize maxBytes,
            @DefaultValue("1280") @Min(1) int maxSide,
            @DefaultValue("300") @Min(1) int maxFrames,
            @DefaultValue("256000000") @Min(1) long maxTotalPixels) {
    }
}
