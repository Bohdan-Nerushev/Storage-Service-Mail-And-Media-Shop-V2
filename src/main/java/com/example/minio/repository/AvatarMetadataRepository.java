package com.example.minio.repository;

import com.example.minio.entity.AvatarMetadata;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AvatarMetadataRepository extends JpaRepository<AvatarMetadata, Long> {

    Optional<AvatarMetadata> findBySubject(String subject);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select avatar from AvatarMetadata avatar where avatar.subject = :subject")
    Optional<AvatarMetadata> findBySubjectForUpdate(@Param("subject") String subject);
}
