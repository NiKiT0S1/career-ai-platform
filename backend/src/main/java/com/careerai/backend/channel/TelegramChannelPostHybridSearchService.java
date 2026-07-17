package com.careerai.backend.channel;

import com.careerai.backend.semantic.ChannelPostSemanticMatch;
import com.careerai.backend.semantic.ChannelPostSemanticSearchService;
import com.careerai.backend.semantic.EmbeddingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Объединяет structured и semantic search
 * отдельно для каждой категории запроса.
 */

@Service
public class TelegramChannelPostHybridSearchService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramChannelPostHybridSearchService.class
            );

    private final TelegramChannelPostStructuredSearchService
            structuredSearchService;

    private final ChannelPostSemanticSearchService
            semanticSearchService;

    public TelegramChannelPostHybridSearchService(
            TelegramChannelPostStructuredSearchService structuredSearchService,
            ChannelPostSemanticSearchService semanticSearchService
    ) {
        this.structuredSearchService =
                structuredSearchService;

        this.semanticSearchService =
                semanticSearchService;
    }

    public ChannelPostSearchResult findRelevantPosts(
            ChannelQueryAnalysis analysis,
            Optional<EmbeddingResult> queryEmbedding,
            int totalLimit
    ) {
        if (analysis == null
                || !analysis.needsChannelPosts()) {
            return ChannelPostSearchResult.empty();
        }

        int safeTotalLimit =
                Math.max(1, totalLimit);

        List<ChannelContentScope> scopes =
                analysis.contentScopes();

        if (scopes == null || scopes.isEmpty()) {
            return ChannelPostSearchResult.empty();
        }

        List<ChannelPostSearchGroup> groups =
                new ArrayList<>();

        int remainingLimit = safeTotalLimit;

        for (ChannelContentScope scope : scopes) {
            if (remainingLimit <= 0
                    || scope == ChannelContentScope.NONE) {
                break;
            }

            int scopeLimit = calculateScopeLimit(
                    analysis,
                    scopes.size(),
                    remainingLimit,
                    safeTotalLimit
            );

            List<TelegramChannelPost> structuredPosts =
                    structuredSearchService
                            .findRelevantPostsForScope(
                                    analysis,
                                    scope,
                                    scopeLimit
                            );

            Optional<List<ChannelPostSemanticMatch>>
                    semanticResult =
                    queryEmbedding.flatMap(
                            embedding ->
                                    semanticSearchService
                                            .findRelevantPosts(
                                                    embedding,
                                                    scope
                                            )
                    );

            List<TelegramChannelPost> mergedPosts =
                    mergeScopeResults(
                            structuredPosts,
                            semanticResult,
                            analysis.resultMode(),
                            scopeLimit
                    );

            if (!mergedPosts.isEmpty()) {
                groups.add(
                        new ChannelPostSearchGroup(
                                scope,
                                mergedPosts
                        )
                );

                remainingLimit -= mergedPosts.size();
            }

            log.info(
                    "Hybrid channel scope completed. "
                            + "scope={}, mode={}, structured={}, "
                            + "semantic={}, selected={}",
                    scope,
                    analysis.resultMode(),
                    structuredPosts.size(),
                    semanticResult
                            .map(List::size)
                            .orElse(0),
                    mergedPosts.size()
            );
        }

        ChannelPostSearchResult result =
                new ChannelPostSearchResult(groups);

        log.info(
                "Hybrid channel search completed. "
                        + "scopes={}, mode={}, groups={}, "
                        + "selected={}",
                analysis.contentScopes(),
                analysis.resultMode(),
                groups.size(),
                result.allPosts().size()
        );

        return result;
    }

    private int calculateScopeLimit(
            ChannelQueryAnalysis analysis,
            int scopeCount,
            int remainingLimit,
            int totalLimit
    ) {
        if (analysis.resultMode()
                == ChannelResultMode.ALL_MATCHING) {
            return remainingLimit;
        }

        int balancedLimit =
                Math.max(1, totalLimit / scopeCount);

        return Math.min(
                balancedLimit,
                remainingLimit
        );
    }

    private List<TelegramChannelPost> mergeScopeResults(
            List<TelegramChannelPost> structuredPosts,
            Optional<List<ChannelPostSemanticMatch>>
                    semanticResult,
            ChannelResultMode resultMode,
            int limit
    ) {
        List<TelegramChannelPost> result =
                new ArrayList<>();

        Set<Long> addedIds =
                new LinkedHashSet<>();

        if (resultMode
                == ChannelResultMode.ALL_MATCHING) {

            /*
             * Для полного списка сначала гарантированно
             * сохраняем все structured-записи.
             */
            for (TelegramChannelPost post : structuredPosts) {
                addPost(result, addedIds, post, limit);
            }

            /*
             * Затем добавляем смысловые уточнения,
             * например отмены или исправления типа OTHER.
             */
            semanticResult.ifPresent(matches -> {
                for (ChannelPostSemanticMatch match : matches) {
                    addPost(
                            result,
                            addedIds,
                            match.post(),
                            limit
                    );
                }
            });

            return sortNewestFirst(result);
        }

        if (semanticResult.isEmpty()
                || semanticResult.get().isEmpty()) {

            for (TelegramChannelPost post : structuredPosts) {
                addPost(result, addedIds, post, limit);
            }

            return sortNewestFirst(result);
        }

        List<ChannelPostSemanticMatch> semanticMatches =
                semanticResult.get();

        Set<Long> structuredIds =
                new LinkedHashSet<>();

        for (TelegramChannelPost post : structuredPosts) {
            if (post != null && post.getId() != null) {
                structuredIds.add(post.getId());
            }
        }

        /*
         * Сначала пересечение structured + semantic.
         */
        for (ChannelPostSemanticMatch match : semanticMatches) {
            TelegramChannelPost post = match.post();

            if (post != null
                    && post.getId() != null
                    && structuredIds.contains(post.getId())) {

                addPost(
                        result,
                        addedIds,
                        post,
                        limit
                );
            }
        }

        /*
         * Затем semantic-only уточнения.
         */
        for (ChannelPostSemanticMatch match : semanticMatches) {
            addPost(
                    result,
                    addedIds,
                    match.post(),
                    limit
            );
        }

        /*
         * Оставшиеся structured-записи.
         */
        for (TelegramChannelPost post : structuredPosts) {
            addPost(
                    result,
                    addedIds,
                    post,
                    limit
            );
        }

        return sortNewestFirst(result);
    }

    private List<TelegramChannelPost> sortNewestFirst(
            List<TelegramChannelPost> posts
    ) {
        return posts.stream()
                .sorted(
                        Comparator.comparing(
                                this::effectiveDate,
                                Comparator.nullsLast(
                                        Comparator.reverseOrder()
                                )
                        )
                )
                .toList();
    }

    private OffsetDateTime effectiveDate(
            TelegramChannelPost post
    ) {
        if (post == null) {
            return null;
        }

        if (post.getEditedAt() != null) {
            return post.getEditedAt();
        }

        if (post.getPostedAt() != null) {
            return post.getPostedAt();
        }

        return post.getCreatedAt();
    }

    private void addPost(
            List<TelegramChannelPost> result,
            Set<Long> addedIds,
            TelegramChannelPost post,
            int limit
    ) {
        if (result.size() >= limit
                || post == null
                || post.getId() == null
                || !post.isSearchable()) {
            return;
        }

        if (addedIds.add(post.getId())) {
            result.add(post);
        }
    }
}