# socialflux-media

Biblioteca compartilhada de **mídia** dos serviços Spring Boot do Social Flux: validação e re-codificação de imagens estáticas
(PNG, JPEG, WebP, com correção da orientação EXIF), sanitização de GIFs animados e armazenamento de objetos S3 compatível
(**RustFS** em desenvolvimento). Usada pelo `profile_service` (avatares) e pelo `post_service` (anexos de postagens).
Spring Boot 4.1 · Java 21 · auto-configuração (basta estar no classpath).

Decisões: D9, D15–D18 de [`../../docs/plano-dominio-postagens-servico-proprio.md`](../../docs/plano-dominio-postagens-servico-proprio.md) (§9).

## O que ela entrega

| Bean / classe | Para que serve |
| --- | --- |
| `ImageProcessor` | `fit(bytes)`: PNG/JPEG/WebP → reduz até o lado maior ≤ `image.max-side` (nunca amplia), aplica a orientação EXIF e re-codifica (JPEG 0,85 se opaca, PNG se houver transparência), sem metadados. `squareJpeg(bytes, lado)`: recorte quadrado central, sempre JPEG (avatares) |
| `GifSanitizer` | `sanitize(bytes)`: valida o GIF por blocos e regrava só quadros, tempos, transparência e laço; descarta comentários, extensões desconhecidas e bytes após o fim; não redimensiona |
| `MediaProcessor` | `process(bytes)`: encaminha GIF para o `GifSanitizer` e o resto para `ImageProcessor.fit` (anexos de postagem) |
| `ProcessingLimiter` | Semáforo de processamentos simultâneos (`max-concurrent-processing`); sem vaga em `acquire-timeout` → `MediaException(BUSY)` |
| `MediaStorage` | `put`, `get`, `delete`, `exists`, `list` por bucket e chave. `S3MediaStorage` (padrão) ou `InMemoryMediaStorage` (`storage=memory`, só testes) |
| `MediaKeys` | `newKey(prefixo, ext, clock)` → `po_<uuidv7>.jpg`; `isValid(prefixo, chave)` (formato estrito); `contentTypeOf(chave)` |
| `MediaFormat` | Detecção pelos bytes mágicos (PNG, JPEG, WebP, GIF); nome e `Content-Type` do cliente nunca contam |
| `MediaException` | Recusa com `Reason` (`EMPTY`, `TOO_LARGE`, `DIMENSIONS_TOO_LARGE`, `INVALID`, `ANIMATED`, `GIF_INVALID`, `BUSY`); cada serviço traduz para o seu `codigo` público |

O que **não** está aqui: regras de acesso (quem pode ver cada objeto), nomes de bucket, rotas HTTP e o formato de erro — tudo
isso é de cada serviço.

### Imagens estáticas (D9)

1. tamanho em bytes; 2. bytes mágicos (PNG, JPEG, `RIFF…WEBP`); 3. PNG animado (APNG) e WebP animado → `ANIMATED`
("para animações, envie um GIF"); 4. lê só o cabeçalho e recusa `largura × altura > image.max-pixels` antes de decodificar;
5. lê a orientação EXIF (JPEG APP1, chunk `eXIf` do PNG, bloco `EXIF` do WebP; valor inválido = sem orientação);
6. decodifica (JPEG CMYK/YCCK convertido para RGB pelo TwelveMonkeys); 7. reduz em passos de metade; 8. aplica a orientação
nos pixels (largura/altura trocadas nas orientações 5–8); 9. codifica de novo sem metadados. O arquivo enviado nunca é guardado.

### GIFs (D16)

Cabeçalho `GIF87a`/`GIF89a`, tela lógica (0 ou acima de `gif.max-side` → recusa), tabelas de cores, quadros (LZW mínimo
entre 2 e 8, sub-blocos até `0x00`), extensões. Recusa: estrutura malformada ou truncada, introdutor desconhecido, nenhum
quadro, quadros acima de `gif.max-frames`, `largura × altura × quadros` acima de `gif.max-total-pixels`, arquivo acima de
`gif.max-bytes` (antes e depois da regravação).

## Como usar num serviço

1. Dependência no `pom.xml`:
   ```xml
   <dependency>
       <groupId>com.socialflux</groupId>
       <artifactId>socialflux-media</artifactId>
       <version>0.1.0-SNAPSHOT</version>
   </dependency>
   ```
2. Configuração no `application.yml`, apontando para as variáveis do serviço:
   ```yaml
   socialflux:
     media:
       storage: ${MEU_SERVICO_MEDIA_STORAGE:s3}
       s3:
         endpoint: ${RUSTFS_ENDPOINT:http://localhost:9000}
         region: ${RUSTFS_REGION:us-east-1}
         access-key: ${RUSTFS_ACCESS_KEY:}
         secret-key: ${RUSTFS_SECRET_KEY:}
   ```
3. Nos testes: `socialflux.media.storage=memory` (e, se quiser inspecionar, injete `MediaStorage` e faça cast para
   `InMemoryMediaStorage`).
4. Traduza `MediaException.reason()` para o código de erro público do serviço.

## Configuração (`socialflux.media.*`)

| Chave | Padrão | Descrição |
| --- | --- | --- |
| `storage` | `s3` | `s3` ou `memory` (testes) |
| `s3.endpoint` | — | Obrigatória com `storage=s3`: `http://rustfs:9000` (containers) ou `http://localhost:9000` (host) |
| `s3.region` | `us-east-1` | O RustFS não exige; o SDK precisa de uma |
| `s3.access-key` / `s3.secret-key` | — | Obrigatórias com `storage=s3`. Endereçamento path-style sempre; checksums só quando exigidos |
| `image.max-bytes` / `image.max-pixels` / `image.max-side` | `5MB` / `16000000` / `1600` | D9 |
| `gif.max-bytes` / `gif.max-side` / `gif.max-frames` / `gif.max-total-pixels` | `8MB` / `1280` / `300` / `256000000` | D16 |
| `max-concurrent-processing` | `4` | Processamentos simultâneos (uma imagem de 16 MP ocupa ~64 MB decodificada) |
| `acquire-timeout` | `PT5S` | Espera por vaga antes de `BUSY` |

## Dependências (D17, versões fixadas no `pom.xml`)

SDK S3 da AWS (`s3` + `url-connection-client`; clientes Apache/Netty excluídos) e TwelveMonkeys ImageIO (`imageio-webp`,
`imageio-jpeg`, `imageio-metadata`) — este último lê arquivo enviado pelo usuário: mantenha-o atualizado.

## Build e testes

Na raiz: `mvn -pl libs/socialflux-media install` (os serviços dependem dela) ou `mvn -pl <serviço> -am test`. Os testes não
precisam de Docker (armazenamento em memória; arquivos de exemplo em `src/test/resources/fixtures`, gerados com o Pillow).

**Teste manual contra o RustFS** (`S3MediaStorageIntegrationTest`, pulado sem as variáveis):
```bash
RUSTFS_IT_ENDPOINT=http://localhost:9000 RUSTFS_IT_ACCESS_KEY=... RUSTFS_IT_SECRET_KEY=... RUSTFS_IT_BUCKET=post-media \
  mvn -pl libs/socialflux-media test -Dtest=S3MediaStorageIntegrationTest
```
Registro: **ainda não executado** (Docker parado na implementação, 2026-11-15). Ao rodar, anote aqui a data, a versão da
imagem do RustFS e o resultado.
