package com.careerai.backend.channel;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    @Query("""
            SELECT metadata
            FROM TelegramChannelPostMetadata metadata
            JOIN FETCH metadata.post post
            WHERE metadata.extractionStatus = 'SUCCESS'
              AND metadata.postType IN :postTypes
            ORDER BY COALESCE(post.editedAt, post.postedAt, post.createdAt) DESC
            """)
    List<TelegramChannelPostMetadata> findLatestSuccessfulByPostTypes(
            @Param("postTypes") Collection<TelegramChannelPostType> postTypes,
            Pageable pageable
    );

    @Query("""
            SELECT metadata
            FROM TelegramChannelPostMetadata metadata
            JOIN FETCH metadata.post post
            WHERE metadata.extractionStatus = 'SUCCESS'
              AND metadata.postType = 'VACANCY'
            ORDER BY COALESCE(post.editedAt, post.postedAt, post.createdAt) DESC
            """)
    List<TelegramChannelPostMetadata> findLatestSuccessfulVacancies(Pageable pageable);

    @Query("""
            SELECT metadata
            FROM TelegramChannelPostMetadata metadata
            JOIN FETCH metadata.post post
            WHERE metadata.extractionStatus = 'SUCCESS'
              AND metadata.postType = 'VACANCY'
              AND (
                    LOWER(COALESCE(metadata.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(COALESCE(metadata.company, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(COALESCE(metadata.technologies, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(COALESCE(metadata.levelText, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(COALESCE(metadata.summary, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            ORDER BY COALESCE(post.editedAt, post.postedAt, post.createdAt) DESC
            """)
    List<TelegramChannelPostMetadata> findLatestSuccessfulVacanciesByKeyword(
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
            SELECT metadata
            FROM TelegramChannelPostMetadata metadata
            JOIN FETCH metadata.post post
            WHERE metadata.extractionStatus = 'SUCCESS'
              AND (
                    metadata.postType = 'PRACTICE'
                 OR metadata.relevantForPractice = true
              )
            ORDER BY COALESCE(post.editedAt, post.postedAt, post.createdAt) DESC
            """)
    List<TelegramChannelPostMetadata> findLatestSuccessfulPracticeRelated(Pageable pageable);

    Collection<TelegramChannelPostType> post(TelegramChannelPost post);
}
