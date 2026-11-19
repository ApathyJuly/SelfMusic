package com.selfmusic.server.storage;

import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local")
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(AppProperties properties) {
        this.root = Path.of(properties.getStorage().getLocal().getRoot()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(root);
            log.info("Local storage initialized at {}", root);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建本地存储目录: " + root, exception);
        }
    }

    @Override
    public void put(String key, InputStream input, long size, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("本地文件写入失败: " + key, exception);
        }
    }

    @Override
    public InputStream open(String key, long start, long endInclusive) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw ApiException.notFound("媒体文件不存在");
        }
        try {
            InputStream input = Files.newInputStream(target);
            if (start > 0) {
                input.skipNBytes(start);
            }
            return input;
        } catch (IOException exception) {
            throw new IllegalStateException("本地文件读取失败: " + key, exception);
        }
    }

    @Override
    public StorageMetadata head(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw ApiException.notFound("媒体文件不存在");
        }
        try {
            String contentType = Files.probeContentType(target);
            long modified = Files.getLastModifiedTime(target).toMillis();
            String etag = "\"" + Long.toHexString(Files.size(target)) + "-" + Long.toHexString(modified) + "\"";
            return new StorageMetadata(Files.size(target), contentType == null ? "application/octet-stream" : contentType, etag);
        } catch (IOException exception) {
            throw new IllegalStateException("本地文件信息读取失败: " + key, exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            log.warn("Failed to delete local object {}: {}", key, exception.getMessage());
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw ApiException.badRequest("invalid_key", "非法的对象路径");
        }
        return target;
    }
}
