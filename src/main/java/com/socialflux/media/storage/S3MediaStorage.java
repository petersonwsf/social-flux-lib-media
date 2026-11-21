package com.socialflux.media.storage;

import com.socialflux.media.MediaProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Armazenamento S3 compatível (RustFS em desenvolvimento; MinIO ou S3 só com outra configuração). Endereçamento por caminho
 * (path-style) e checksums só quando a operação exige: o RustFS não é idêntico à AWS, e os checksums novos do SDK são o
 * ponto mais comum de incompatibilidade.
 */
public class S3MediaStorage implements MediaStorage, AutoCloseable {

    private final S3Client client;

    public S3MediaStorage(S3Client client) {
        this.client = client;
    }

    public static S3MediaStorage create(MediaProperties.S3 config) {
        if (isBlank(config.endpoint()) || isBlank(config.accessKey()) || isBlank(config.secretKey())) {
            throw new IllegalStateException("socialflux.media.s3.endpoint, access-key e secret-key são obrigatórios "
                    + "com socialflux.media.storage=s3");
        }
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(config.endpoint()))
                .region(Region.of(config.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30))
                        .build())
                .build();
        return new S3MediaStorage(client);
    }

    @Override
    public void put(String bucket, String key, byte[] bytes, String contentType) {
        client.putObject(b -> b.bucket(bucket).key(key).contentType(contentType).contentLength((long) bytes.length),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public Optional<StoredObject> get(String bucket, String key) {
        try {
            ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(b -> b.bucket(bucket).key(key));
            return Optional.of(new StoredObject(response.asByteArray(), response.response().contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public boolean exists(String bucket, String key) {
        try {
            client.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public List<StoredKey> list(String bucket, String prefix) {
        return client.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
                .map(o -> new StoredKey(o.key(), o.lastModified()))
                .toList();
    }

    @Override
    public void close() {
        client.close();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
