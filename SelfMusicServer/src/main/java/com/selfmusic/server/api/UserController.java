package com.selfmusic.server.api;

import com.selfmusic.server.api.dto.UserDto;
import com.selfmusic.server.auth.AuthService;
import com.selfmusic.server.security.UserPrincipal;
import com.selfmusic.server.user.AvatarPayload;
import com.selfmusic.server.user.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final ProfileService profileService;

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal UserPrincipal principal) {
        return authService.me(principal.id());
    }

    @PutMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserDto uploadAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestPart("file") MultipartFile file
    ) {
        return profileService.uploadAvatar(principal.id(), file);
    }

    @GetMapping("/me/avatar")
    public ResponseEntity<byte[]> avatar(@AuthenticationPrincipal UserPrincipal principal) {
        AvatarPayload payload = profileService.loadAvatar(principal.id());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(payload.bytes());
    }

    @DeleteMapping("/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAvatar(@AuthenticationPrincipal UserPrincipal principal) {
        profileService.deleteAvatar(principal.id());
    }
}
