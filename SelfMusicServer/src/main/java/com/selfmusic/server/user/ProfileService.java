package com.selfmusic.server.user;

import com.selfmusic.server.api.dto.UserDto;
import com.selfmusic.server.auth.AuthService;
import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.storage.StorageMetadata;
import com.selfmusic.server.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    private final UserRepository userRepository;
    private final StorageService storageService;

    @Transactional
    public UserDto uploadAvatar(String userId, MultipartFile file) {
        User user = requireUser(userId);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("avatar_empty", "请选择一张图片");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw ApiException.badRequest("avatar_too_large", "头像不能超过 5 MB");
        }
        String contentType = resolveContentType(file);
        if (contentType == null) {
            throw ApiException.badRequest("avatar_type", "头像仅支持 JPEG、PNG 或 WebP");
        }
        String key = "users/" + userId + "/avatar." + EXTENSIONS.get(contentType);
        try (InputStream input = file.getInputStream()) {
            storageService.put(key, input, file.getSize(), contentType);
        } catch (IOException exception) {
            throw new IllegalStateException("头像保存失败", exception);
        }
        String previous = user.getAvatarObjectKey();
        user.setAvatarObjectKey(key);
        userRepository.save(user);
        if (previous != null && !previous.equals(key)) {
            storageService.delete(previous);
        }
        return AuthService.toDto(user);
    }

    @Transactional(readOnly = true)
    public AvatarPayload loadAvatar(String userId) {
        User user = requireUser(userId);
        String key = user.getAvatarObjectKey();
        if (key == null || !storageService.exists(key)) {
            throw ApiException.notFound("尚未设置头像");
        }
        StorageMetadata metadata = storageService.head(key);
        try (InputStream input = storageService.open(key, 0, Math.max(0, metadata.size() - 1))) {
            return new AvatarPayload(input.readAllBytes(), metadata.contentType());
        } catch (IOException exception) {
            throw new IllegalStateException("头像读取失败", exception);
        }
    }

    @Transactional
    public void deleteAvatar(String userId) {
        User user = requireUser(userId);
        String key = user.getAvatarObjectKey();
        user.setAvatarObjectKey(null);
        userRepository.save(user);
        if (key != null) {
            storageService.delete(key);
        }
    }

    private User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("用户不存在"));
    }

    private static String resolveContentType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            String normalized = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
            if (EXTENSIONS.containsKey(normalized)) {
                return normalized;
            }
        }
        String name = file.getOriginalFilename();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return "image/jpeg";
            }
            if (lower.endsWith(".png")) {
                return "image/png";
            }
            if (lower.endsWith(".webp")) {
                return "image/webp";
            }
        }
        return null;
    }
}
