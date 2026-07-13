package com.careerai.backend.semantic;

import com.careerai.backend.faq.FaqEntry;
import com.careerai.backend.faq.FaqEntryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Создаёт и обновляет embeddings для активных FAQ-записей.
 */

@Service
public class FaqSemanticIndexer {

    private static final Logger log = LoggerFactory.getLogger(FaqSemanticIndexer.class);

    private final SemanticSearchProperties properties;
    private final FaqEntryService faqEntryService;
    private final EmbeddingProvider embeddingProvider;
    private final SemanticEmbeddingRepository embeddingRepository;
    private final SemanticContentHashService contentHashService;

    public FaqSemanticIndexer(
            SemanticSearchProperties properties,
            FaqEntryService faqEntryService,
            EmbeddingProvider embeddingProvider,
            SemanticEmbeddingRepository embeddingRepository,
            SemanticContentHashService contentHashService
    ) {
        this.properties = properties;
        this.faqEntryService = faqEntryService;
        this.embeddingProvider = embeddingProvider;
        this.embeddingRepository = embeddingRepository;
        this.contentHashService = contentHashService;
    }

    /**
     * Запускает индексацию после полного запуска Spring Boot,
     * только если включены оба feature flag.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void indexFaqEntriesOnStartup() {
        if (!properties.isEnabled()) {
            log.info("FAQ semantic indexing skipped: semantic search is disabled");
            return;
        }

        if (!properties.isIndexFaqOnStartup()) {
            log.info("FAQ semantic indexing skipped: startup indexing is disabled");
            return;
        }

        indexAllActiveFaqEntries();
    }

    public void indexAllActiveFaqEntries() {
        List<FaqEntry> faqEntries = faqEntryService.findActiveEntries();

        int indexed = 0;
        int skipped = 0;
        int failed = 0;

        log.info(
                "FAQ semantic indexing started. Active entries: {}",
                faqEntries.size()
        );

        for (FaqEntry faqEntry : faqEntries) {
            try {
                if (faqEntry.getId() == null) {
                    failed++;

                    log.warn(
                            "FAQ semantic indexing skipped an entry without id. slug={}",
                            faqEntry.getSlug()
                    );

                    continue;
                }

                String documentContent = buildDocumentContent(faqEntry);

                String contentHash =
                        contentHashService.calculateHash(documentContent);

                boolean upToDate = embeddingRepository.isUpToDate(
                        SemanticSourceType.FAQ,
                        faqEntry.getId(),
                        contentHash,
                        properties.getEmbeddingModel(),
                        properties.getOutputDimensions()
                );

                if (upToDate) {
                    skipped++;
                    continue;
                }

                EmbeddingResult embeddingResult =
                        embeddingProvider.embedDocument(
                                faqEntry.getQuestion(),
                                documentContent
                        );

                if (embeddingResult.failed()) {
                    failed++;

                    log.warn(
                            "FAQ embedding generation failed. id={}, slug={}, error={}",
                            faqEntry.getId(),
                            faqEntry.getSlug(),
                            embeddingResult.errorMessage()
                    );

                    continue;
                }

                if (embeddingResult.values().length
                        != properties.getOutputDimensions()) {

                    failed++;

                    log.warn(
                            "FAQ embedding has unexpected dimensions. id={}, expected={}, actual={}",
                            faqEntry.getId(),
                            properties.getOutputDimensions(),
                            embeddingResult.values().length
                    );

                    continue;
                }

                embeddingRepository.saveOrUpdate(
                        SemanticSourceType.FAQ,
                        faqEntry.getId(),
                        contentHash,
                        embeddingResult.model(),
                        embeddingResult.values()
                );

                indexed++;

                log.info(
                        "FAQ embedding saved. id={}, slug={}, dimensions={}",
                        faqEntry.getId(),
                        faqEntry.getSlug(),
                        embeddingResult.values().length
                );
            }
            catch (Exception exception) {
                failed++;

                log.error(
                        "Unexpected error during FAQ semantic indexing. id={}, slug={}",
                        faqEntry.getId(),
                        faqEntry.getSlug(),
                        exception
                );
            }
        }

        long storedFaqEmbeddings =
                embeddingRepository.countBySourceType(
                        SemanticSourceType.FAQ
                );

        log.info(
                "FAQ semantic indexing finished. indexed={}, skipped={}, failed={}, stored={}",
                indexed,
                skipped,
                failed,
                storedFaqEmbeddings
        );
    }

    private String buildDocumentContent(FaqEntry faqEntry) {
        return """
                Источник: FAQ Центра карьеры и трудоустройства AITU
                Категория: %s
                Вопрос: %s
                Краткий ответ: %s
                Полный ответ: %s
                Ключевые слова: %s
                """.formatted(
                safeText(faqEntry.getCategory()),
                safeText(faqEntry.getQuestion()),
                safeText(faqEntry.getShortAnswer()),
                safeText(faqEntry.getFullAnswer()),
                safeText(faqEntry.getKeywords())
        ).trim();
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
