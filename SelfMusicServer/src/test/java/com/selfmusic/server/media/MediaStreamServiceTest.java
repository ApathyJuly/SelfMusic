package com.selfmusic.server.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaStreamServiceTest {

    private final MediaStreamService service = new MediaStreamService();

    @Test
    void parsesByteRanges() {
        assertThat(service.parseRange(null, 100)).isNull();
        assertThat(service.parseRange("bytes=0-9", 100)).isEqualTo(new MediaStreamService.Range(0, 9));
        assertThat(service.parseRange("bytes=10-", 100)).isEqualTo(new MediaStreamService.Range(10, 99));
        assertThat(service.parseRange("bytes=-10", 100)).isEqualTo(new MediaStreamService.Range(90, 99));
        assertThat(service.parseRange("bytes=0-999", 100)).isEqualTo(new MediaStreamService.Range(0, 99));
    }

    @Test
    void rejectsInvalidRanges() {
        assertThatThrownBy(() -> service.parseRange("bytes=100-", 100)).isInstanceOf(InvalidRangeException.class);
        assertThatThrownBy(() -> service.parseRange("bytes=10-5", 100)).isInstanceOf(InvalidRangeException.class);
        assertThatThrownBy(() -> service.parseRange("items=0-5", 100)).isInstanceOf(InvalidRangeException.class);
        assertThatThrownBy(() -> service.parseRange("bytes=0-1,3-4", 100)).isInstanceOf(InvalidRangeException.class);
    }

    @Test
    void buildsUtf8ContentDisposition() {
        String value = service.contentDisposition("夜曲.mp3");
        assertThat(value).contains("attachment").contains("filename*=UTF-8''");
    }
}
