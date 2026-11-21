# Arquitetura — socialflux-media

Biblioteca (jar, sem `main`) com a mídia compartilhada pelos serviços Spring Boot. Registrada por auto-configuração; cada
serviço continua dono das regras de acesso, dos buckets, das rotas e do formato de erro. Uso e configuração: [`../README.md`](../README.md).

```
upload ─► MediaProcessor ──GIF──► GifSanitizer (valida e regrava por blocos)
                          └resto─► ImageProcessor (bytes mágicos → animação? → dimensões → EXIF → decodifica → reduz → orienta → JPEG/PNG)
                (ambos dentro do ProcessingLimiter)
serviço ─► MediaStorage ─► S3MediaStorage (RustFS/MinIO/S3, path-style)   |   InMemoryMediaStorage (testes)
```

## Árvore de diretórios

```
libs/socialflux-media/
├── pom.xml                       parent spring-boot-starter-parent 4.1.2; SDK S3 (BOM fixado) e TwelveMonkeys fixados
├── .gitignore                    ignora target/ e arquivos de IDE
├── README.md                     o que a biblioteca entrega, como usar, configuração, teste manual no RustFS
├── docs/ARCHITECTURE.md          este arquivo
└── src/
    ├── main/java/com/socialflux/media/
    │   ├── image/                imagens estáticas (inspeção binária, orientação, processador)
    │   ├── gif/                  sanitizador de GIF
    │   └── storage/              armazenamento de objetos
    ├── main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    ├── test/java/com/socialflux/media/
    └── test/resources/fixtures/  WebP (opaco, transparente, animado, com EXIF), APNG, JPEG CMYK, PNG com EXIF
```

## Arquivos (`com.socialflux.media`)

| Arquivo | O que representa |
| --- | --- |
| `SocialFluxMediaAutoConfiguration.java` | Auto-configuração: limitador, `ImageProcessor`, `GifSanitizer`, `MediaProcessor` e o `MediaStorage` de `storage` (s3/memory) |
| `MediaProperties.java` | Propriedades `socialflux.media.*` (armazenamento, S3, limites de imagem e GIF, simultâneos), validadas ao subir |
| `MediaException.java` | Recusa de arquivo com `Reason` estável; sem HTTP (cada serviço traduz) |
| `MediaFormat.java` | Detecção do formato pelos bytes mágicos |
| `MediaKeys.java` | Geração (`<prefixo>_<uuidv7>.<ext>`) e validação estrita de chaves; `Content-Type` pela extensão; UUIDv7 |
| `MediaProcessor.java` | Encaminha o anexo para o sanitizador de GIF ou para o processador de imagens |
| `ProcessedMedia.java` | Resultado: bytes novos, `Content-Type`, extensão, largura, altura e tipo (`IMAGE`/`GIF`) |
| `ProcessingLimiter.java` | Semáforo justo com espera limitada; sem vaga → `BUSY` |
| `image/ImageInspector.java` | Leitura binária limitada: orientação EXIF (JPEG APP1, PNG `eXIf`, WebP `EXIF`, TIFF II/MM) e animação (APNG `acTL`, WebP `VP8X`/`ANIM`/`ANMF`) |
| `image/Orientation.java` | Aplica as 8 orientações EXIF nos pixels (tabela no Javadoc) |
| `image/ImageProcessor.java` | Pipeline das imagens estáticas (D9): `fit` para postagens e `squareJpeg` para avatares |
| `gif/GifSanitizer.java` | Leitor/regravador de GIF por blocos com limites de tamanho, dimensão, quadros e pixels (D16) |
| `storage/MediaStorage.java` | Interface de armazenamento (bucket + chave) e os registros `StoredObject`/`StoredKey` |
| `storage/S3MediaStorage.java` | Implementação com o SDK S3: endpoint configurável, path-style, checksums só quando exigidos, cliente HTTP do JDK |
| `storage/InMemoryMediaStorage.java` | Implementação em memória para testes (com auxiliares `putAt`, `count`, `clear`) |

## Testes (`src/test`)

| Arquivo | O que cobre |
| --- | --- |
| `ImageProcessorTest.java` | As 8 orientações EXIF em JPEG (cantos e dimensões), orientação em PNG e WebP, ausência de EXIF/"GPS" na saída, orientação inválida, WebP opaco → JPEG e transparente → PNG, PNG com alfa opaco → JPEG, JPEG CMYK, WebP/PNG animados recusados, arquivo falso, GIF no processador de imagens, truncado, dimensões declaradas gigantes, limite de bytes, redução sem ampliar, avatar quadrado com orientação |
| `GifSanitizerTest.java` | GIF de 1 e de vários quadros (decodificado de novo pelo JDK), laço preservado, comentário/extensão desconhecida/aplicação estranha descartados, bytes após o fim descartados, truncado, quadros demais, bomba de pixels, tabela de cores inconsistente, introdutor inválido, sem quadros, PNG renomeado, 0×0, dimensão e tamanho acima do limite |
| `MediaSupportTest.java` | Limitador saturado/recuperado e liberação em falha, chaves (formato, prefixo, traversal, extensão), detecção de formato, armazenamento em memória |
| `AutoConfigurationTest.java` | Beans registrados com `storage=memory`, S3 como padrão exigindo endpoint/chaves, limite inválido falha ao subir |
| `S3MediaStorageIntegrationTest.java` | Manual: put/get/list/delete contra RustFS real (só com `RUSTFS_IT_ENDPOINT`) |
| `support/Images.java` | Geração de imagens por quadrantes, inserção de EXIF em JPEG, leitura de fixtures, comparação de cores |
