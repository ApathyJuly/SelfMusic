package com.selfmusic.server.media;

import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaTokenServiceTest {

    private MediaTokenService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getMedia().setTokenSecret("unit-test-media-secret-0123456789abcdef");
        properties.getMedia().setTokenTtl(Duration.ofMinutes(30));
        properties.getMedia().setCoverTokenTtl(Duration.ofDays(1));
        service = new MediaTokenService(properties);
    }

    @Test
    void issuesAndVerifiesToken() {
        String token = service.issue("song-1", "user-1", "audio", Duration.ofMinutes(5), true);
        MediaTokenService.Payload payload = service.verify(token);

        assertThat(payload.subjectId()).isEqualTo("song-1");
        assertThat(payload.userId()).isEqualTo("user-1");
        assertThat(payload.kind()).isEqualTo("audio");
        assertThat(payload.download()).isTrue();
    }

    @Test
    void rejectsTamperedToken() {
        String token = service.issue("song-1", "user-1", "audio", Duration.ofMinutes(5), false);
        char[] chars = token.toCharArray();
        chars[0] = chars[0] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String token = service.issue("song-1", "user-1", "audio", Duration.ofSeconds(-1), false);

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(ApiException.class);
    }
}
