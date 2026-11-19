package com.selfmusic.server.media;

import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class MediaTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AppProperties properties;
    private final byte[] secret;

    public MediaTokenService(AppProperties properties) {
        this.properties = properties;
        this.secret = properties.getMedia().getTokenSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.media.token-secret 至少需要 32 字节，请通过 MEDIA_TOKEN_SECRET 配置");
        }
    }

    public String issue(String subjectId, String userId, String kind, Duration ttl, boolean download) {
        long expiresAt = Instant.now().plus(ttl).getEpochSecond();
        String payload = String.join("|",
                subjectId,
                userId,
                kind,
                Long.toString(expiresAt),
                download ? "1" : "0"
        );
        String signature = sign(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + "|" + signature).getBytes(StandardCharsets.UTF_8));
    }

    public Payload verify(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int signatureIndex = decoded.lastIndexOf('|');
            if (signatureIndex <= 0) {
                throw ApiException.unauthorized("媒体链接无效");
            }
            String payload = decoded.substring(0, signatureIndex);
            String signature = decoded.substring(signatureIndex + 1);
            if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8))) {
                throw ApiException.unauthorized("媒体链接无效");
            }
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 5) {
                throw ApiException.unauthorized("媒体链接无效");
            }
            long expiresAt = Long.parseLong(parts[3]);
            if (Instant.now().getEpochSecond() > expiresAt) {
                throw ApiException.unauthorized("媒体链接已过期，请刷新播放地址");
            }
            return new Payload(parts[0], parts[1], parts[2], expiresAt, "1".equals(parts[4]));
        } catch (IllegalArgumentException exception) {
            throw ApiException.unauthorized("媒体链接无效");
        }
    }

    public Duration audioTtl() {
        return properties.getMedia().getTokenTtl();
    }

    public Duration coverTtl() {
        return properties.getMedia().getCoverTokenTtl();
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成媒体签名", exception);
        }
    }

    public record Payload(String subjectId, String userId, String kind, long expiresAt, boolean download) {
    }
}
