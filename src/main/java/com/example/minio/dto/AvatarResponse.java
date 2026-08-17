package com.example.minio.dto;

import com.example.minio.entity.AvatarMetadata;

import java.time.Instant;

public record AvatarResponse(boolean hasAvatar, long version, String contentType, long size, Instant updatedAt) {

    public static AvatarResponse from(final AvatarMetadata metadata) {
        return new AvatarResponse(true, metadata.getVersion(), metadata.getContentType(), metadata.getSize(),
                metadata.getUpdatedAt());
    }

    public static AvatarResponse absent() {
        return new AvatarResponse(false, 0L, null, 0L, null);
    }
}
