package com.selfmusic.server.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PlayUrlsRequest(
        @NotEmpty
        @Size(max = 500, message = "一次最多获取 500 首歌的播放地址")
        List<String> songIds
) {
}
