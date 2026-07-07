package com.careerai.backend.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Репозиторий для структурированной metadata Telegram-постов.
 */

public interface TelegramChannelPostMetadataRepository extends JpaRepository<TelegramChannelPostMetadata, Long> {

    Optional<TelegramChannelPostMetadata> findByPostId(long postId);

    boolean existsByPostId(Long postId);
}
