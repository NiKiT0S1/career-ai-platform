package com.careerai.backend.semantic;

import com.careerai.backend.channel.TelegramChannelPost;
import com.careerai.backend.channel.TelegramChannelPostRepository;
import com.careerai.backend.channel.TelegramChannelPostSavedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Создаёт и обновляет embeddings для Telegram-постов канала.
 *
 * При запуске приложения сервис может проиндексировать последние посты.
 * Новые и отредактированные посты индексируются автоматически
 * после успешного сохранения в базе.
 */

@Service
public class ChannelPostSemanticIndexer {

    private static final Logger log = LoggerFactory.getLogger(ChannelPostSemanticIndexer.class);

    private final SemanticSearchProperties properties;
    private final TelegramChannelPostRepository postRepository;
    private final EmbeddingProvider embeddingProvider;
    private final SemanticEmbeddingRepository embeddingRepository;
    private final SemanticContentHashService contentHashService;

    public ChannelPostSemanticIndexer(
            SemanticSearchProperties properties,
            TelegramChannelPostRepository postRepository,
            EmbeddingProvider embeddingProvider,
            SemanticEmbeddingRepository embeddingRepository,
            SemanticContentHashService contentHashService
    ) {
        this.properties = properties;
        this.postRepository = postRepository;
        this.embeddingProvider = embeddingProvider;
        this.embeddingRepository = embeddingRepository;
        this.contentHashService = contentHashService;
    }

    /**
     * Выполняет первичную индексацию последних постов
     * после полного запуска приложения.
     */
    @EventListener
    public void indexChannelPostsOnStartup(ApplicationReadyEvent event) {
        if (!properties.isEnabled()) {
            log.info("Channel post semantic indexing skipped: semantic search is disabled");
            return;
        }

        if (!properties.isIndexChannelPostsOnStartup()) {
            log.info("Channel post semantic indexing skipped: startup indexing is disabled");
            return;
        }

        indexLatestPosts();
    }

    /**
     * Индексирует новый или отредактированный пост
     * только после успешного commit основной транзакции.
     */
    @Async("channelPostIndexingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleSavedPost(TelegramChannelPostSavedEvent event) {
        if (!properties.isEnabled()) {
            return;
        }

        postRepository.findById(event.postId())
                .ifPresentOrElse(
                        this::indexSinglePostSafely,
                        () -> log.warn(
                                "Channel post semantic indexing skipped: post was not found. postId={}",
                                event.postId()
                        )
                );
    }

    public void indexLatestPosts() {
        int limit = Math.max(1, properties.getChannelIndexLimit());

        List<TelegramChannelPost> posts = postRepository.findLatestTextPosts(
                PageRequest.of(0, limit)
        );

        int indexed = 0;
        int skipped = 0;
        int deleted = 0;
        int failed = 0;

        log.info(
                "Channel post semantic indexing started. Posts selected: {}, limit={}",
                posts.size(),
                limit
        );

        for (TelegramChannelPost post : posts) {
            IndexingResult result = indexSinglePostSafely(post);

            switch (result) {
                case INDEXED -> indexed++;
                case SKIPPED ->  skipped++;
                case DELETED -> deleted++;
                case FAILED -> failed++;
            }
        }

        long storedEmbeddings = embeddingRepository.countBySourceType(
                SemanticSourceType.CHANNEL_POST
        );

        log.info(
                "Channel post semantic indexing finished. indexed={}, skipped={}, deleted={}, failed={}, stored={}",
                indexed,
                skipped,
                deleted,
                failed,
                storedEmbeddings
        );
    }

    private IndexingResult indexSinglePostSafely(TelegramChannelPost post) {
        try {
            return indexSinglePost(post);
        }
        catch (Exception e) {
            log.error(
                    "Unexpected error during channel post semantic indexing. postId={}",
                    post == null ? null : post.getId(),
                    e
            );

            return IndexingResult.FAILED;
        }
    }

    private IndexingResult indexSinglePost(TelegramChannelPost post) {
        if (post == null || post.getId() == null) {
            return IndexingResult.FAILED;
        }

        if (post.getText() == null || post.getText().isBlank()) {
            int deleted = embeddingRepository.delete(
                    SemanticSourceType.CHANNEL_POST,
                    post.getId()
            );

            if (deleted > 0) {
                log.info(
                        "Empty channel post embedding deleted. postId={}",
                        post.getId()
                );
            }

            return IndexingResult.DELETED;
        }

        String documentContent = buildDocumentContent(post);

        String contentHash = contentHashService.calculateHash(documentContent);

        boolean upToDate = embeddingRepository.isUpToDate(
                SemanticSourceType.CHANNEL_POST,
                post.getId(),
                contentHash,
                properties.getEmbeddingModel(),
                properties.getOutputDimensions()
        );

        if (upToDate) {
            return IndexingResult.SKIPPED;
        }

        EmbeddingResult embeddingResult = embeddingProvider.embedDocument(
                buildDocumentTitle(post),
                documentContent
        );

        if (embeddingResult.failed()) {
            log.warn(
                    "Channel post embedding generation failed. postId={}, error={}",
                    post.getId(),
                    embeddingResult.errorMessage()
            );

            return IndexingResult.FAILED;
        }

        if (embeddingResult.values().length != properties.getOutputDimensions()) {
            log.warn(
                    "Channel post embedding has unexpected dimensions. postId={}, expected={}, actual={}",
                    post.getId(),
                    properties.getOutputDimensions(),
                    embeddingResult.values().length
            );

            return IndexingResult.FAILED;
        }

        embeddingRepository.saveOrUpdate(
                SemanticSourceType.CHANNEL_POST,
                post.getId(),
                contentHash,
                embeddingResult.model(),
                embeddingResult.values()
        );

        log.info(
                "Channel post embedding saved. postId={}, telegramMessageId={}, dimensions={}",
                post.getId(),
                post.getTelegramMessageId(),
                embeddingResult.values().length
        );

        return IndexingResult.INDEXED;
    }

    private String buildDocumentTitle(TelegramChannelPost post) {
        String channelTitle = safeText(post.getChannelTitle());

        if (channelTitle.isBlank()) {
            return "Telegram-пост Центра карьеры и трудоустройства AITU";
        }

        return "Telegram-пост канала " + channelTitle;
    }

    private String buildDocumentContent(TelegramChannelPost post) {
        return """
                Источник: Telegram-канал Центра карьеры и трудоустройства AITU
                Канал: %s
                Имя канала: %s
                Дата публикации: %s
                Дата редактирования: %s
                Текст поста:
                %s
                """.formatted(
                safeText(post.getChannelTitle()),
                safeText(post.getChannelUsername()),
                formatDate(post.getPostedAt()),
                formatDate(post.getEditedAt()),
                safeText(post.getText())
        ).trim();
    }

    private String formatDate(OffsetDateTime dateTime) {
        return dateTime == null
                ? ""
                : dateTime.toString();
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private enum IndexingResult {
        INDEXED,
        SKIPPED,
        DELETED,
        FAILED
    }
}
