package com.selfmusic.server.storage;

import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
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

import java.io.InputStream;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3", matchIfMissing = true)
public class S3StorageService implements StorageService {

    private final S3Client s3;
    private final AppProperties properties;

    public S3StorageService(S3Client s3, AppProperties properties) {
        this.s3 = s3;
        this.properties = properties;
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

    private String bucket() {
        return properties.getStorage().getS3().getBucket();
    }
}
