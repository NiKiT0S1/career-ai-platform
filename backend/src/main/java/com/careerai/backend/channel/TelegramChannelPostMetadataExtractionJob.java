package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Фоновая задача для обработки Telegram-постов, ожидающих metadata extraction.
 *
 * Job периодически берёт несколько PENDING-записей и запускает extractor.
 * Это сделано отдельно от Telegram polling, чтобы сохранение постов из канала
 * не тормозилось долгими LLM-запросами.
 */

@Service
public class TelegramChannelPostMetadataExtractionJob {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelPostMetadataExtractionJob.class);

    private static final int BATCH_SIZE = 3;

    private final TelegramChannelPostMetadataRepository metadataRepository;
    private final TelegramChannelPostMetadataExtractorService extractorService;

    public TelegramChannelPostMetadataExtractionJob(
            TelegramChannelPostMetadataRepository metadataRepository,
            TelegramChannelPostMetadataExtractorService extractorService
    ) {
        this.metadataRepository = metadataRepository;
        this.extractorService = extractorService;
    }

    @Scheduled(
            initialDelayString = "${careerai.channel.metadata-extraction.initial-delay-ms:10000}",
            fixedDelayString = "${careerai.channel.metadata-extraction.fixed-delay-ms:30000}"
    )
    public void processPendingMetadata() {
        List<TelegramChannelPostMetadata> pendingMetadata = metadataRepository.findLatestByExtractionStatus(
                TelegramChannelPostExtractionStatus.PENDING,
                PageRequest.of(0, BATCH_SIZE)
        );

        if (pendingMetadata.isEmpty()) {
            return;
        }

        log.warn("Processing pending Telegram channel post metadata. count={}", pendingMetadata.size());

        for (TelegramChannelPostMetadata metadata : pendingMetadata) {
            extractorService.extractAndSave(metadata.getId());
        }
    }
}
