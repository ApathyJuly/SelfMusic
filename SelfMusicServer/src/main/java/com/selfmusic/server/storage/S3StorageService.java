package com.selfmusic.server.storage;

import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3", matchIfMissing = true)
public class S3StorageService implements StorageService {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final AppProperties properties;

    public S3StorageService(S3Client s3, AppProperties properties) {
        this.s3 = s3;
        this.properties = properties;
        this.presigner = createPresigner(properties);
    }

    @PreDestroy
    void closePresigner() {
        presigner.close();
    }

    private static S3Presigner createPresigner(AppProperties properties) {
        AppProperties.S3 s3 = properties.getStorage().getS3();
        var builder = S3Presigner.builder()
                .region(Region.of(s3.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.isPathStyle())
                        .build());
        String endpoint = s3.getPublicEndpoint() == null || s3.getPublicEndpoint().isBlank()
                ? s3.getEndpoint()
                : s3.getPublicEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (s3.getAccessKey() != null && !s3.getAccessKey().isBlank()
                && s3.getSecretKey() != null && !s3.getSecretKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())
            ));
        }
        return builder.build();
    }

    @PostConstruct
    void ensureBucket() {
        String bucket = bucket();
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("S3 bucket '{}' is ready", bucket);
        } catch (S3Exception exception) {
            int status = exception.statusCode();
            if ((status == 404 || exception instanceof software.amazon.awssdk.services.s3.model.NoSuchBucketException)
                    && properties.getStorage().getS3().isCreateBucket()) {
                try {
                    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                    log.info("Created S3 bucket '{}'", bucket);
                } catch (S3Exception createError) {
                    log.warn("Unable to create S3 bucket '{}': {}", bucket, createError.getMessage());
                }
            } else {
                log.warn("S3 bucket '{}' check failed (HTTP {}): {}", bucket, status, exception.getMessage());
            }
        }
    }

    @Override
    public void put(String key, InputStream input, long size, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket())
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();
        try (InputStream stream = input) {
            s3.putObject(request, RequestBody.fromInputStream(stream, size));
        } catch (Exception exception) {
            throw new IllegalStateException("对象存储写入失败: " + key, exception);
        }
    }

    @Override
    public InputStream open(String key, long start, long endInclusive) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket())
                .key(key)
                .range("bytes=" + start + "-" + endInclusive)
                .build();
        ResponseInputStream<GetObjectResponse> response = s3.getObject(request);
        return response;
    }

    @Override
    public StorageMetadata head(String key) {
        try {
            HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .build());
            return new StorageMetadata(
                    response.contentLength(),
                    response.contentType() == null ? "application/octet-stream" : response.contentType(),
                    response.eTag()
            );
        } catch (NoSuchKeyException exception) {
            throw ApiException.notFound("媒体文件不存在");
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw ApiException.notFound("媒体文件不存在");
            }
            throw new IllegalStateException("对象存储读取失败: " + key, exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket()).key(key).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                log.warn("Failed to delete S3 object {}: {}", key, exception.getMessage());
            }
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket()).key(key).build());
            return true;
        } catch (S3Exception exception) {
            return false;
        }
    }

    @Override
    public boolean supportsPresignedUrls() {
        return true;
    }

    @Override
    public String presignedGetUrl(String key, Duration ttl, String contentDisposition, String contentType) {
        GetObjectRequest.Builder request = GetObjectRequest.builder()
                .bucket(bucket())
                .key(key);
        if (contentDisposition != null && !contentDisposition.isBlank()) {
            request.responseContentDisposition(contentDisposition);
        }
        if (contentType != null && !contentType.isBlank()) {
            request.responseContentType(contentType);
        }
        PresignedGetObjectRequest presigned = presigner.presignGetObject(builder -> builder
                .signatureDuration(ttl)
                .getObjectRequest(request.build()));
        return presigned.url().toString();
    }

    @Override
    public String presignedPutUrl(String key, Duration ttl, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket())
                .key(key)
                .contentType(contentType)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(builder -> builder
                .signatureDuration(ttl)
                .putObjectRequest(request));
        return presigned.url().toString();
    }

    private String bucket() {
        return properties.getStorage().getS3().getBucket();
    }
}
