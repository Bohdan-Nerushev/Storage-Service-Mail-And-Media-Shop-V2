package com.example.minio.controller;

import com.example.minio.dto.FileResponse;
import com.example.minio.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import com.example.minio.entity.FileMetadata;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/files")
@Tag(
    name = "File Management", 
    description = "Endpoints for uploading and storing files using MinIO object storage"
)
public class FileController {

    private final FileStorageService storageService;

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Operation(
            summary = "Upload file to storage",
            description = "Receives a file, uploads it to MinIO, and persists metadata to database",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "File upload successful",
                            content = @Content(
                                    schema = @Schema(
                                             implementation = FileResponse.class
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request payload",
                            content = @Content
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error occurred",
                            content = @Content
                    )
            }
    )
    public @NotNull FileResponse upload(
            @Parameter(
                    description = "Multipart file to upload",
                    required = true)
            @RequestParam(name = "file") final @NotNull MultipartFile file
        ) {
        log.info("[CorrelationId: {}] Initiating file upload. Original filename: {}", MDC.get("correlationId"), file.getOriginalFilename());
        return this.storageService.upload(file);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Download file from storage",
            description = "Retrieves a file by its database ID and streams it back to the client",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "File downloaded successfully",
                            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "File not found",
                            content = @Content
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error occurred",
                            content = @Content
                    )
            }
    )
    public ResponseEntity<Resource> download(
            @Parameter(
                    description = "ID of the file to download",
                    required = true)
            @PathVariable(name = "id") final @NotNull Long id
    ) {
        log.info("[CorrelationId: {}] Initiating file download for ID: {}", MDC.get("correlationId"), id);
        
        final FileMetadata metadata = this.storageService.getMetadata(id);
        final InputStream stream = this.storageService.downloadFile(id);
        
        final Resource resource = new InputStreamResource(stream);
        
        final String contentType = metadata.getContentType();
        final String safeContentType = contentType != null ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + metadata.getOriginalName() + "\"")
                .contentLength(metadata.getSize())
                .contentType(MediaType.parseMediaType(safeContentType))
                .body(resource);
    }
}

