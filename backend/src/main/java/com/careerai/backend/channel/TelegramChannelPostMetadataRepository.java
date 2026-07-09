package com.careerai.backend.channel;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для структурированной metadata Telegram-постов.
 */

public interface TelegramChannelPostMetadataRepository extends JpaRepository<TelegramChannelPostMetadata, Long> {

    Optional<TelegramChannelPostMetadata> findByPostId(long postId);

    boolean existsByPostId(Long postId);

    @Query("""
            SELECT metadata
            FROM TelegramChannelPostMetadata metadata
            JOIN FETCH metadata.post post
            WHERE metadata.extractionStatus = :status
            ORDER BY COALESCE(post.editedAt, post.postedAt, post.createdAt) DESC
            """)
    List<TelegramChannelPostMetadata> findLatestByExtractionStatus(
            @Param("status") TelegramChannelPostExtractionStatus status,
            Pageable pageable
    );
}
