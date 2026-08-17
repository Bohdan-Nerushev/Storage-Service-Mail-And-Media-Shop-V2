package com.example.minio.controller;

import com.example.minio.dto.AvatarContent;
import com.example.minio.dto.AvatarResponse;
import com.example.minio.service.AvatarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/avatars/me")
@PreAuthorize("hasRole('USER')")
@Tag(name = "Avatars", description = "Authenticated current-user avatar operations")
public class AvatarController {

    private final AvatarService avatarService;

    public AvatarController(final AvatarService avatarService) {
        this.avatarService = avatarService;
    }

    @GetMapping
    @Operation(summary = "Get current avatar metadata")
    public AvatarResponse getAvatar(@AuthenticationPrincipal final Jwt jwt) {
        return avatarService.getAvatar(jwt.getSubject());
    }

    @GetMapping("/content")
    @Operation(summary = "Get current avatar image")
    public ResponseEntity<InputStreamResource> getAvatarContent(@AuthenticationPrincipal final Jwt jwt) {
        final AvatarContent content = avatarService.getContent(jwt.getSubject());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
                .eTag("\"" + content.version() + "\"")
                .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.size())
                .body(new InputStreamResource(content.stream()));
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Create or replace current avatar",
            responses = {
                    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AvatarResponse.class))),
                    @ApiResponse(responseCode = "413", description = "Avatar exceeds the size limit"),
                    @ApiResponse(responseCode = "415", description = "Avatar is not a supported image")
            })
    public AvatarResponse replaceAvatar(
            @AuthenticationPrincipal final Jwt jwt,
            @RequestParam("file") final MultipartFile file) {
        return avatarService.replaceAvatar(jwt.getSubject(), file);
    }

    @DeleteMapping
    @Operation(summary = "Delete current avatar", responses = @ApiResponse(responseCode = "204"))
    public ResponseEntity<Void> deleteAvatar(@AuthenticationPrincipal final Jwt jwt) {
        avatarService.deleteAvatar(jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
