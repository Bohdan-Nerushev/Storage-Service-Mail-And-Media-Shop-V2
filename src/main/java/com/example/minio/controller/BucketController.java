package com.example.minio.controller;

import com.example.minio.dto.BucketDto;
import com.example.minio.service.BucketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/buckets")
@Tag(
    name = "Bucket Management", 
    description = "Endpoints for managing MinIO storage buckets"
)
public class BucketController {

    private final BucketService bucketService;

    @PostMapping
    @Operation(
            summary = "Create bucket",
            description = "Creates a new object storage bucket if it doesn't already exist",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Bucket created successfully",
                            content = @Content(schema = @Schema(implementation = BucketDto.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request or empty bucket name",
                            content = @Content
                    ),
                    @ApiResponse(
                            responseCode = "409",
                            description = "Bucket already exists",
                            content = @Content
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error occurred",
                            content = @Content
                    )
            }
    )
    public ResponseEntity<BucketDto> createBucket(
            @Parameter(description = "Name of the new bucket", required = true)
            @RequestParam(name = "name") final @NotBlank String name
    ) {
        log.info("[CorrelationId: {}] Initiating bucket creation: {}", MDC.get("correlationId"), name);
        final BucketDto bucket = this.bucketService.createBucket(name);
        return ResponseEntity.ok(bucket);
    }

    @GetMapping
    @Operation(
            summary = "List buckets",
            description = "Retrieves a list of all available object storage buckets",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Buckets retrieved successfully",
                            content = @Content(schema = @Schema(implementation = BucketDto.class))
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error occurred",
                            content = @Content
                    )
            }
    )
    public ResponseEntity<List<BucketDto>> listBuckets() {
        log.info("[CorrelationId: {}] Listing all buckets", MDC.get("correlationId"));
        return ResponseEntity.ok(this.bucketService.listBuckets());
    }

    @DeleteMapping("/{name}")
    @Operation(
            summary = "Delete bucket",
            description = "Deletes a bucket from storage. If force=true, clears all files inside before deletion",
            responses = {
                    @ApiResponse(
                            responseCode = "204",
                            description = "Bucket deleted successfully",
                            content = @Content
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Bucket is not empty and force is false, or invalid request",
                            content = @Content
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Bucket not found",
                            content = @Content
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Internal server error occurred",
                            content = @Content
                    )
            }
    )
    public ResponseEntity<Void> deleteBucket(
            @Parameter(description = "Name of the bucket to delete", required = true)
            @PathVariable(name = "name") final @NotBlank String name,
            @Parameter(description = "If true, deletes all contents inside the bucket prior to bucket deletion")
            @RequestParam(name = "force", defaultValue = "false") final boolean force
    ) {
        log.info("[CorrelationId: {}] Initiating bucket deletion: {}, force: {}", MDC.get("correlationId"), name, force);
        try {
            this.bucketService.deleteBucket(name, force);
            return ResponseEntity.noContent().build();
        } catch (final IllegalArgumentException e) {
            log.warn("[CorrelationId: {}] Deletion failed for bucket {}: {}", MDC.get("correlationId"), name, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
