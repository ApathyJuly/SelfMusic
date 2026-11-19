package com.selfmusic.server.auth;

import com.selfmusic.server.api.dto.AuthResponse;
import com.selfmusic.server.api.dto.LoginRequest;
import com.selfmusic.server.api.dto.RegisterRequest;
import com.selfmusic.server.api.dto.UserDto;
import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.security.JwtService;
import com.selfmusic.server.user.User;
import com.selfmusic.server.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public AuthResponse register(RegisterRequest request, String userAgent) {
        String username = normalize(request.username());
        if (userRepository.existsByUsername(username)) {
            throw ApiException.conflict("username_taken", "用户名已被使用");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        String displayName = request.displayName() == null ? "" : request.displayName().trim();
        user.setDisplayName(displayName.isBlank() ? username : displayName);
        userRepository.save(user);
        return issueTokens(user, userAgent);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String userAgent) {
        User user = userRepository.findByUsername(normalize(request.username()))
                .orElseThrow(() -> ApiException.unauthorized("用户名或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        return issueTokens(user, userAgent);
    }

    @Transactional
    public AuthResponse refresh(String rawRefreshToken, String userAgent) {
        RefreshToken old = refreshTokenService.consume(rawRefreshToken);
        User user = userRepository.findById(old.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("用户不存在"));
        return issueTokens(user, userAgent);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    @Transactional(readOnly = true)
    public UserDto me(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("用户不存在"));
        return toDto(user);
    }

    private AuthResponse issueTokens(User user, String userAgent) {
        String accessToken = jwtService.createAccessToken(user);
        String refreshToken = refreshTokenService.create(user.getId(), userAgent);
        return new AuthResponse(
                accessToken,
                refreshToken,
                "Bearer",
                jwtService.accessTokenTtlSeconds(),
                toDto(user)
        );
    }

    public static UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getUsername(), user.getDisplayName(), user.getCreatedAt());
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
