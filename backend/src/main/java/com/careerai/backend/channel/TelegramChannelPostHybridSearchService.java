package com.careerai.backend.channel;

import com.careerai.backend.semantic.ChannelPostSemanticMatch;
import com.careerai.backend.semantic.ChannelPostSemanticSearchService;
import com.careerai.backend.semantic.EmbeddingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Объединяет structured metadata search
 * и семантический поиск Telegram-постов.
 */

@Service
public class TelegramChannelPostHybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelPostHybridSearchService.class);

    private final TelegramChannelPostStructuredSearchService structuredSearchService;
    private final ChannelPostSemanticSearchService semanticSearchService;

    public TelegramChannelPostHybridSearchService(
            TelegramChannelPostStructuredSearchService structuredSearchService,
            ChannelPostSemanticSearchService semanticSearchService
    ) {
        this.structuredSearchService = structuredSearchService;
        this.semanticSearchService = semanticSearchService;
    }

    public List<TelegramChannelPost> findRelevantPosts(
            ChannelQueryAnalysis analysis,
            Optional<EmbeddingResult> queryEmbedding,
            int limit
    ) {
        int safeLimit = Math.max(1, limit);

        List<TelegramChannelPost> structuredPosts = structuredSearchService.findRelevantPosts(analysis, safeLimit);

        Optional<List<ChannelPostSemanticMatch>> semanticResult = queryEmbedding.flatMap(semanticSearchService::findRelevantPosts);

        if (semanticResult.isEmpty()) {
            log.info(
                    "Hybrid channel search used structured fallback. structured={}",
                    structuredPosts.size()
            );

            return structuredPosts.stream()
                    .limit(safeLimit)
                    .toList();
        }

        List<ChannelPostSemanticMatch> semanticMatches = semanticResult.get();

        List<TelegramChannelPost> result = new ArrayList<>();
        Set<Long> addedPostIds = new LinkedHashSet<>();

        Set<Long> structuredPostIds = structuredPosts.stream()
                .map(TelegramChannelPost::getId)
                .filter(id -> id != null)
                .collect(
                        LinkedHashSet::new,
                        Set::add,
                        Set::addAll
                );

        /*
         * Сначала добавляем посты, найденные одновременно
         * structured и semantic поиском.
         *
         * Это наиболее уверенные результаты.
         */
        for (ChannelPostSemanticMatch match : semanticMatches) {
            TelegramChannelPost post = match.post();

            if (post.getId() != null
                    && structuredPostIds.contains(post.getId())) {

                addPost(
                        result,
                        addedPostIds,
                        post,
                        safeLimit
                );
            }
        }

        /*
         * Затем добавляем семантически подходящие посты,
         * которые metadata-поиск мог пропустить.
         */
        for (ChannelPostSemanticMatch match : semanticMatches) {
            addPost(
                    result,
                    addedPostIds,
                    match.post(),
                    safeLimit
            );
        }

        /*
         * Оставшиеся места заполняем результатами
         * structured metadata search.
         */
        for (TelegramChannelPost post : structuredPosts) {
            addPost(
                    result,
                    addedPostIds,
                    post,
                    safeLimit
            );
        }

        long overlapCount = semanticMatches.stream()
                .map(ChannelPostSemanticMatch::post)
                .map(TelegramChannelPost::getId)
                .filter(id -> id != null)
                .filter(structuredPostIds::contains)
                .count();

        log.info(
                "Hybrid channel search completed. structured={}, semantic={}, overlap={}, selected={}",
                structuredPosts.size(),
                semanticMatches.size(),
                overlapCount,
                result.size()
        );

        return List.copyOf(result);
    }

    private void addPost(
            List<TelegramChannelPost> result,
            Set<Long> addedPostIds,
            TelegramChannelPost post,
            int limit
    ) {
        if (result.size() >= limit) {
            return;
        }

        if (post == null || post.getId() == null) {
            return;
        }

        if (addedPostIds.add(post.getId())) {
            result.add(post);
        }
    }
}
