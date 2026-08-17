package com.example.minio.dto;

import java.io.InputStream;

public record AvatarContent(InputStream stream, String contentType, long size, long version) {
}
