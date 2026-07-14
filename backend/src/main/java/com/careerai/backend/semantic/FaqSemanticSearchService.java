package com.careerai.backend.semantic;

import com.careerai.backend.faq.FaqEntry;
import com.careerai.backend.faq.FaqEntryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ищет FAQ-записи, смысл которых ближе всего
 * к вопросу пользователя.
 */

@Service
public class FaqSemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(FaqSemanticSearchService.class);

    private final SemanticSearchProperties properties;
    private final EmbeddingProvider embeddingProvider;
    private final SemanticEmbeddingRepository embeddingRepository;
    private final FaqEntryService faqEntryService;

    public FaqSemanticSearchService(
            SemanticSearchProperties properties,
            EmbeddingProvider embeddingProvider,
            SemanticEmbeddingRepository embeddingRepository,
            FaqEntryService faqEntryService
    ) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        this.embeddingRepository = embeddingRepository;
        this.faqEntryService = faqEntryService;
    }

    /**
     * Возвращает Optional.empty(), если Semantic Search недоступен
     * и вызывающий код должен использовать fallback до стабильной версии без Semantic.
     *
     * Возвращает Optional со списком, если поиск был выполнен.
     */
    public Optional<List<FaqEntry>> findRelevantEntries(String userMessage) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }

        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }

        EmbeddingResult queryEmbedding = embeddingProvider.embedQuery(userMessage);

        if (queryEmbedding.failed()) {
            log.warn(
                    "FAQ semantic search fallback: query embedding failed. error={}",
                    queryEmbedding.errorMessage()
            );

            return Optional.empty();
        }

        List<SemanticEmbeddingVector> storedEmbeddings = embeddingRepository.findBySourceType(
                SemanticSourceType.FAQ,
                properties.getEmbeddingModel(),
                properties.getOutputDimensions()
        );

        if (storedEmbeddings.isEmpty()) {
            log.warn(
                    "FAQ semantic search fallback: no FAQ embeddings were found"
            );

            return Optional.empty();
        }

        Map<Long, FaqEntry> activeFaqById =
                faqEntryService.findActiveEntries()
                        .stream()
                        .filter(entry -> entry.getId() != null)
                        .collect(Collectors.toMap(
                                FaqEntry::getId,
                                Function.identity()
                        ));

        List<FaqSemanticMatch> matches = storedEmbeddings.stream()
                .map(storedEmbedding -> {
                    FaqEntry faqEntry =
                            activeFaqById.get(storedEmbedding.sourceId());

                    if (faqEntry == null) {
                        return null;
                    }

                    double similarity = cosineSimilarity(
                            queryEmbedding.values(),
                            storedEmbedding.values()
                    );

                    return new FaqSemanticMatch(
                            faqEntry,
                            similarity
                    );
                })
                .filter(Objects::nonNull)
                .filter(match ->
                        match.similarity() >= properties.getFaqMinSimilarity()
                )
                .sorted(
                        Comparator.comparingDouble(
                                FaqSemanticMatch::similarity
                        ).reversed()
                )
                .limit(properties.getFaqMaxResults())
                .toList();

        log.info(
                "FAQ semantic search completed. candidates={}, selected={}, threshold={}, matches={}",
                storedEmbeddings.size(),
                matches.size(),
                properties.getFaqMinSimilarity(),
                formatMatches(matches)
        );

        return Optional.of(
                matches.stream()
                        .map(FaqSemanticMatch::entry)
                        .toList()
        );
    }

    private double cosineSimilarity(
            double[] firstVector,
            double[] secondVector
    ) {
        if (firstVector == null || secondVector == null) {
            return -1.0;
        }

        if (firstVector.length == 0
                || firstVector.length != secondVector.length) {
            return -1.0;
        }

        double dotProduct = 0.0;
        double firstNorm = 0.0;
        double secondNorm = 0.0;

        for (int i = 0; i < firstVector.length; i++) {
            dotProduct += firstVector[i] * secondVector[i];
            firstNorm += firstVector[i] *  firstVector[i];
            secondNorm += secondVector[i] * secondVector[i];
        }

        if (firstNorm == 0.0 || secondNorm == 0.0) {
            return -1.0;
        }

        return dotProduct / (Math.sqrt(firstNorm) *  Math.sqrt(secondNorm));
    }

    private String formatMatches(List<FaqSemanticMatch> matches) {
        if (matches.isEmpty()) {
            return "none";
        }

        return matches.stream()
                .map(match -> "%s=%.4f".formatted(
                        match.entry().getSlug(),
                        match.similarity()
                ))
                .collect(Collectors.joining(", "));
    }
}
