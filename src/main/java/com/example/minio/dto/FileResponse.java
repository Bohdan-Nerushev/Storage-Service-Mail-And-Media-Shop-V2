package com.example.minio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FileResponse(
        @NotNull Long id,
        @NotBlank String name
) {}
