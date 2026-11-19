package com.selfmusic.server.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "只能包含字母、数字或下划线，长度 3-32")
        String username,

        @NotBlank
        @Size(min = 8, max = 72, message = "长度需为 8-72")
        String password,

        @Size(max = 100)
        String displayName
) {
}
