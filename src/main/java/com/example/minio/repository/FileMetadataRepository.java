package com.example.minio.repository;

import com.example.minio.entity.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {

    Optional<FileMetadata> findByBucketNameAndObjectKey(String bucketName, String objectKey);

    List<FileMetadata> findAllByBucketName(String bucketName);

}
