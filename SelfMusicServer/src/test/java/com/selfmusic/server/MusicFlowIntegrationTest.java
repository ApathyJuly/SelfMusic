package com.selfmusic.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MusicFlowIntegrationTest {

    private static final byte[] AUDIO = buildAudio();
    private static final byte[] COVER = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final String LYRIC = "[00:00.00]hello\n[00:01.00]world\n";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanStorage() throws IOException {
        Path root = Path.of("target/test-storage");
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 测试清理失败不影响断言
                    }
                });
            }
        }
    }

    @Test
    void fullMusicLifecycle() throws Exception {
        String token = registerAndToken();
        JsonNode playlist = createPlaylist(token, "My Playlist", true);
        String playlistId = playlist.path("id").asText();
        assertThat(playlist.path("coverUrl").asText()).startsWith("/api/v1/media/playlists/");

        JsonNode song = uploadSong(token, playlistId, "First Song", true);
        String songId = song.path("id").asText();
        assertThat(song.path("title").asText()).isEqualTo("First Song");
        assertThat(song.path("lyricsText").asText()).contains("hello");
        assertThat(song.path("coverUrl").asText()).startsWith("/api/v1/media/songs/");
        assertThat(song.path("createdAt").asText()).isNotBlank();

        mockMvc.perform(get("/api/v1/playlists").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].songCount").value(1))
                .andExpect(jsonPath("$[0].coverUrl").exists());

        mockMvc.perform(get("/api/v1/playlists/{id}/songs", playlistId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(songId))
                .andExpect(jsonPath("$[0].lyricsText").value(LYRIC));

        // 重命名歌单时同步歌曲的 album 字段
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/playlists/{id}", playlistId)
                        .param("name", "Renamed Playlist")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Playlist"));

        mockMvc.perform(get("/api/v1/playlists/{id}/songs", playlistId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].album").value("Renamed Playlist"));

        // 播放地址 + Range
        JsonNode play = getJson("/api/v1/songs/" + songId + "/play-url", token);
        String playUrl = play.path("url").asText();
        assertThat(playUrl).startsWith("/api/v1/media/songs/");

        MvcResult rangeResult = mockMvc.perform(get(playUrl).header(HttpHeaders.RANGE, "bytes=0-9"))
                .andExpect(status().isPartialContent())
                .andReturn();
        assertThat(rangeResult.getResponse().getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-9/64");
        assertThat(rangeResult.getResponse().getContentAsByteArray()).hasSize(10);

        MvcResult headResult = mockMvc.perform(head(playUrl))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(headResult.getResponse().getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("64");

        mockMvc.perform(post("/api/v1/songs/play-urls")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"songIds\":[\"" + songId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].songId").value(songId))
                .andExpect(jsonPath("$[0].url", startsWith("/api/v1/media/songs/")));

        MvcResult fullResult = mockMvc.perform(get(playUrl))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(fullResult.getResponse().getContentAsByteArray()).isEqualTo(AUDIO);

        // 下载地址带 attachment
        JsonNode download = getJson("/api/v1/songs/" + songId + "/download-url", token);
        MvcResult downloadResult = mockMvc.perform(get(download.path("url").asText()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(downloadResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("First%20Song");

        // 编辑歌曲
        MockMultipartFile newCover = new MockMultipartFile("cover", "new-cover.png", "image/png", COVER);
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/songs/{id}", songId)
                        .file(newCover)
                        .param("title", "Updated Song")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Song"));

        // 新建第二个歌单并移动
        JsonNode second = createPlaylist(token, "Second Playlist", false);
        String secondId = second.path("id").asText();
        mockMvc.perform(patch("/api/v1/songs/{id}/move", songId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("playlistId", secondId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playlistId").value(secondId))
                .andExpect(jsonPath("$.album").value("Second Playlist"));

        // 收藏
        mockMvc.perform(put("/api/v1/songs/{id}/favorite", songId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favorite\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isFavorite").value(true));

        // 歌词/速度/偏移
        mockMvc.perform(put("/api/v1/songs/{id}/lyrics", songId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lyricsText":"new lyrics","lyricSpeed":1.2,"lyricsOffsetMs":250}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lyricsText").value("new lyrics"))
                .andExpect(jsonPath("$.lyricSpeed").value(1.2))
                .andExpect(jsonPath("$.lyricsOffsetMs").value(250));

        // 删除歌曲与歌单
        mockMvc.perform(delete("/api/v1/songs/{id}", songId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/playlists/{id}/songs", secondId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(delete("/api/v1/playlists/{id}", secondId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
    }

    @Test
    void usersCannotAccessEachOthersMusic() throws Exception {
        String ownerToken = registerAndToken();
        JsonNode playlist = createPlaylist(ownerToken, "Private", false);
        String playlistId = playlist.path("id").asText();
        JsonNode song = uploadSong(ownerToken, playlistId, "Private Song", false);
        String songId = song.path("id").asText();

        String otherToken = registerAndToken();

        mockMvc.perform(get("/api/v1/playlists/{id}/songs", playlistId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/songs/{id}/play-url", songId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/songs/{id}", songId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isNotFound());
    }

    private String registerAndToken() throws Exception {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "username", username,
                                "password", "password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("accessToken").asText();
    }

    private JsonNode createPlaylist(String token, String name, boolean withCover) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/playlists");
        request.param("name", name);
        request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (withCover) {
            request.file(new MockMultipartFile("cover", "cover.png", "image/png", COVER));
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode uploadSong(String token, String playlistId, String title, boolean withAssets) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/playlists/{id}/songs", playlistId);
        request.file(new MockMultipartFile("audio", "song.mp3", "audio/mpeg", AUDIO));
        request.param("title", title);
        request.param("durationMs", "1000");
        request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (withAssets) {
            request.file(new MockMultipartFile("lyric", "song.lrc", "text/plain", LYRIC.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            request.file(new MockMultipartFile("cover", "cover.png", "image/png", COVER));
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode getJson(String path, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static byte[] buildAudio() {
        byte[] bytes = new byte[64];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }
}
