package com.selfmusic.server.auth;

import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final AppProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public String create(String userId, String userAgent) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(hash(raw));
        token.setExpiresAt(Instant.now().plus(properties.getJwt().getRefreshTokenTtl()));
        token.setUserAgent(truncate(userAgent, 255));
        repository.save(token);
        return raw;
    }

    @Transactional
    public RefreshToken consume(String raw) {
        RefreshToken token = repository.findByTokenHash(hash(raw))
                .orElseThrow(() -> ApiException.unauthorized("刷新令牌无效"));
        if (token.isRevoked()) {
            throw ApiException.unauthorized("刷新令牌已失效");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("刷新令牌已过期，请重新登录");
        }
        token.setRevoked(true);
        repository.save(token);
        return token;
    }

    @Transactional
    public void revoke(String raw) {
        repository.findByTokenHash(hash(raw)).ifPresent(token -> {
            token.setRevoked(true);
            repository.save(token);
        });
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void cleanup() {
        int removed = repository.deleteExpiredOrRevoked(Instant.now());
        if (removed > 0) {
            log.info("Removed {} expired or revoked refresh tokens", removed);
        }
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
