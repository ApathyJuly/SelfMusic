package com.selfmusic.server.storage;

import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    @Mock
    private S3Client s3;

    private S3StorageService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getStorage().getS3().setBucket("test-bucket");
        properties.getStorage().getS3().setEndpoint("http://localhost:9000");
        properties.getStorage().getS3().setRegion("us-east-1");
        properties.getStorage().getS3().setPathStyle(true);
        service = new S3StorageService(s3, properties);
    }

    @Test
    void putUploadsToConfiguredBucketWithMetadata() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        when(s3.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        service.put("users/u/songs/s/audio.mp3", new ByteArrayInputStream(content), content.length, "audio/mpeg");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(request.getValue().key()).isEqualTo("users/u/songs/s/audio.mp3");
        assertThat(request.getValue().contentType()).isEqualTo("audio/mpeg");
        assertThat(request.getValue().contentLength()).isEqualTo(content.length);
    }

    @Test
    void presignedUrlsUsePublicEndpointAndPathStyle() {
        AppProperties properties = new AppProperties();
        properties.getStorage().getS3().setBucket("test-bucket");
        properties.getStorage().getS3().setEndpoint("http://minio:9000");
        properties.getStorage().getS3().setPublicEndpoint("https://media.example.com");
        properties.getStorage().getS3().setRegion("us-east-1");
        properties.getStorage().getS3().setAccessKey("test-access");
        properties.getStorage().getS3().setSecretKey("test-secret");
        properties.getStorage().getS3().setPathStyle(true);
        S3StorageService presignedService = new S3StorageService(s3, properties);

        String getUrl = presignedService.presignedGetUrl(
                "users/u/songs/s/audio.mp3", Duration.ofMinutes(5), null, "audio/mpeg");
        String putUrl = presignedService.presignedPutUrl(
                "users/u/songs/s/audio.mp3", Duration.ofMinutes(5), "audio/mpeg");

        assertThat(getUrl).startsWith("https://media.example.com/test-bucket/users/u/songs/s/audio.mp3");
        assertThat(getUrl).contains("X-Amz-Signature");
        assertThat(putUrl).startsWith("https://media.example.com/test-bucket/users/u/songs/s/audio.mp3");
        assertThat(putUrl).contains("X-Amz-Signature");
    }

    @Test
    void headMapsObjectMetadata() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder()
                        .contentLength(42L)
                        .contentType("audio/mpeg")
                        .eTag("\"abc\"")
                        .build()
        );

        StorageMetadata metadata = service.head("users/u/songs/s/audio.mp3");

        assertThat(metadata.size()).isEqualTo(42);
        assertThat(metadata.contentType()).isEqualTo("audio/mpeg");
        assertThat(metadata.etag()).isEqualTo("\"abc\"");
    }

    @Test
    void openRequestsInclusiveByteRange() {
        @SuppressWarnings("unchecked")
        ResponseInputStream<GetObjectResponse> stream = mock(ResponseInputStream.class);
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream);

        InputStream result = service.open("users/u/songs/s/audio.mp3", 10, 19);

        assertThat(result).isSameAs(stream);
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(request.getValue().key()).isEqualTo("users/u/songs/s/audio.mp3");
        assertThat(request.getValue().range()).isEqualTo("bytes=10-19");
    }

    @Test
    void headMissingObjectBecomesNotFound() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        assertThatThrownBy(() -> service.head("missing"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("媒体文件不存在");
    }

    @Test
    void deleteIgnoresMissingObject() {
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        service.delete("missing");
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }
}
