package com.careerai.backend.semantic;

import com.careerai.backend.channel.TelegramChannelPost;
import com.careerai.backend.channel.TelegramChannelPostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ищет Telegram-посты, смысл которых ближе всего
 * к вопросу пользователя.
 */

@Service
public class ChannelPostSemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(ChannelPostSemanticSearchService.class);

    private final SemanticSearchProperties properties;
    private final EmbeddingProvider embeddingProvider;
    private final SemanticEmbeddingRepository embeddingRepository;
    private final TelegramChannelPostRepository postRepository;

    public ChannelPostSemanticSearchService(
        SemanticSearchProperties properties,
        EmbeddingProvider embeddingProvider,
        SemanticEmbeddingRepository embeddingRepository,
        TelegramChannelPostRepository postRepository
    ) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        this.embeddingRepository = embeddingRepository;
        this.postRepository = postRepository;
    }

    /**
     * Optional.empty означает, что Semantic Search недоступен
     * и необходимо использовать structured fallback.
     *
     * Optional со списком означает, что поиск был выполнен,
     * даже если уверенных совпадений не найдено.
     */
    public Optional<List<ChannelPostSemanticMatch>> findRelevantPosts(String userMessage) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }

        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }

        EmbeddingResult queryEmbedding = embeddingProvider.embedQuery(userMessage);

        if (queryEmbedding.failed()) {
            log.warn(
                    "Channel semantic search fallback: query embedding failed. error={}",
                    queryEmbedding.errorMessage()
            );

            return Optional.empty();
        }

        try {
            List<SemanticEmbeddingVector> storedEmbeddings =
                    embeddingRepository.findBySourceType(
                            SemanticSourceType.CHANNEL_POST,
                            properties.getEmbeddingModel(),
                            properties.getOutputDimensions()
                    );

            if (storedEmbeddings.isEmpty()) {
                log.warn(
                        "Channel semantic search fallback: no channel embeddings were found"
                );

                return Optional.empty();
            }

            List<Long> postIds = storedEmbeddings.stream()
                    .map(SemanticEmbeddingVector::sourceId)
                    .toList();

            Map<Long, TelegramChannelPost> postsById =
                    postRepository.findAllById(postIds)
                            .stream()
                            .filter(post -> post.getId() != null)
                            .collect(Collectors.toMap(
                                    TelegramChannelPost::getId,
                                    Function.identity()
                            ));

            int maxResults = Math.max(1, properties.getChannelMaxResults());

            List<ChannelPostSemanticMatch> matches =
                    storedEmbeddings.stream()
                            .map(storedEmbedding -> {
                                TelegramChannelPost post =
                                        postsById.get(
                                                storedEmbedding.sourceId()
                                        );

                                if (post == null) {
                                    return null;
                                }

                                double similarity = cosineSimilarity(
                                        queryEmbedding.values(),
                                        storedEmbedding.values()
                                );

                                return new ChannelPostSemanticMatch(post, similarity);
                            })
                            .filter(Objects::nonNull)
                            .filter(match ->
                                    match.similarity() >= properties.getChannelMinSimilarity()
                            )
                            .sorted(
                                    Comparator.comparingDouble(
                                            ChannelPostSemanticMatch::similarity
                                    ).reversed()
                            )
                            .limit(maxResults)
                            .toList();

            log.info(
                    "Channel semantic search completed. candidates={}, selected={}, threshold={}, matches={}",
                    storedEmbeddings.size(),
                    matches.size(),
                    properties.getChannelMinSimilarity(),
                    formatMatches(matches)
            );

            return Optional.of(matches);
        }
        catch (Exception e) {
            log.error(
                    "Channel semantic search failed. Structured search will be used as fallback",
                    e
            );

            return Optional.empty();
        }
    }

    private double cosineSimilarity(
            double[] firstVector,
            double[] secondVector
    ) {
        if (firstVector ==  null || secondVector == null) {
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
            firstNorm += firstVector[i] * firstVector[i];
            secondNorm += secondVector[i] * secondVector[i];
        }

        if (firstNorm == 0.0 || secondNorm == 0.0) {
            return -1.0;
        }

        return dotProduct / (Math.sqrt(firstNorm) *  Math.sqrt(secondNorm));
    }

    private String formatMatches(List<ChannelPostSemanticMatch> matches) {
        if (matches.isEmpty()) {
            return "none";
        }

        return matches.stream()
                .map(match -> String.format(
                        Locale.ROOT,
                        "postId=%d/messageId=%d=%.4f",
                        match.post().getId(),
                        match.post().getTelegramMessageId(),
                        match.similarity()
                ))
                .collect(Collectors.joining(", "));
    }
}
