package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис для создания и обновления metadata-заготовок Telegram-постов.
 *
 * На этом этапе сервис ещё не извлекает структуру через LLM.
 * Он только создаёт запись со статусом PENDING, чтобы позже extractor
 * мог найти необработанные посты и заполнить структурированные поля.
 */

@Service
public class TelegramChannelPostMetadataService {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelPostMetadataService.class);

    private final TelegramChannelPostMetadataRepository repository;

    public TelegramChannelPostMetadataService(TelegramChannelPostMetadataRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void createOrResetMetadata(TelegramChannelPost post, boolean edited) {
        if (post == null || post.getId() == null) {
            return;
        }

        TelegramChannelPostMetadata metadata = repository
                .findByPostId(post.getId())
                .orElseGet(() -> createNewMetadata(post));

        boolean newMetadata = metadata.getId() == null;

        if (newMetadata || edited) {
            resetToPending(metadata);
        }

        repository.save(metadata);

        log.info(
                "Telegram channel post metadata prepared. postId={}, edited={}, status={}",
                post.getId(),
                edited,
                metadata.getExtractionStatus()
        );
    }

    private TelegramChannelPostMetadata createNewMetadata(TelegramChannelPost post) {
        TelegramChannelPostMetadata metadata = new TelegramChannelPostMetadata();
        metadata.setPost(post);

        return metadata;
    }

    private void resetToPending(TelegramChannelPostMetadata metadata) {
        metadata.setPostType(TelegramChannelPostType.OTHER);

        metadata.setTitle(null);
        metadata.setCompany(null);
        metadata.setTechnologies(null);
        metadata.setLevelText(null);
        metadata.setFormatText(null);

        metadata.setDeadlineText(null);
        metadata.setPracticeStartText(null);
        metadata.setPracticeEndText(null);

        metadata.setSummary(null);
        metadata.setRelevantForPractice(false);

        metadata.setExtractionStatus(TelegramChannelPostExtractionStatus.PENDING);
        metadata.setExtractionError(null);
        metadata.setExtractedAt(null);
    }
}
