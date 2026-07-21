package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Находит корневую публикацию цепочки Telegram-постов.
 *
 * Например:
 *
 * post 102 отвечает на post 101,
 * а post 101 уже связан с post 100.
 *
 * Результатом должен стать post 100.
 */

@Service
public class TelegramChannelPostRelationRootResolver {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelPostRelationRootResolver.class);

    /**
     * Защита от повреждённых или циклических цепочек.
     */
    private static final int MAX_RELATION_DEPTH = 20;

    private final TelegramChannelPostRelationRepository relationRepository;

    public TelegramChannelPostRelationRootResolver(
            TelegramChannelPostRelationRepository relationRepository
    ) {
        this.relationRepository = relationRepository;
    }

    /**
     * Приводит переданный пост к корневой публикации.
     */
    @Transactional(readOnly = true)
    public TelegramChannelPostRootResolution resolveRoot(
            TelegramChannelPost initialPost
    ) {
        if (initialPost == null
                || initialPost.getId() == null) {
            return new TelegramChannelPostRootResolution(
                    null,
                    TelegramChannelPostRootResolutionStatus.POST_MISSING,
                    "Исходный Telegram-пост отсутствует"
            );
        }

        TelegramChannelPost currentPost = initialPost;

        Set<Long> visitedPostIds =
                new LinkedHashSet<>();

        for (int depth = 0;
             depth < MAX_RELATION_DEPTH;
             depth++) {

            Long currentPostId =
                    currentPost.getId();

            if (!visitedPostIds.add(currentPostId)) {
                log.warn("Telegram channel post relation cycle detected. initialPostId={}, currentPostId={}, visitedPostIds={}", initialPost.getId(), currentPostId, visitedPostIds);

                return new TelegramChannelPostRootResolution(
                        null,
                        TelegramChannelPostRootResolutionStatus.CYCLE_DETECTED,
                        "Обнаружена циклическая связь Telegram-постов"
                );
            }

            List<TelegramChannelPostRelation> relations =
                    relationRepository
                            .findAllBySourcePostIdWithTarget(
                                    currentPostId
                            );

            if (relations.isEmpty()) {
                return new TelegramChannelPostRootResolution(
                        currentPost,
                        TelegramChannelPostRootResolutionStatus.RESOLVED,
                        "Корневая публикация найдена"
                );
            }

            Set<Long> targetPostIds =
                    new LinkedHashSet<>();

            for (TelegramChannelPostRelation relation
                    : relations) {

                TelegramChannelPost targetPost =
                        relation.getTargetPost();

                if (targetPost != null
                        && targetPost.getId() != null) {
                    targetPostIds.add(
                            targetPost.getId()
                    );
                }
            }

            if (targetPostIds.size() != 1) {
                log.warn("Telegram channel post root resolution is ambiguous. sourcePostId={}, targetPostIds={}", currentPostId, targetPostIds);

                return new TelegramChannelPostRootResolution(
                        null,
                        TelegramChannelPostRootResolutionStatus.AMBIGUOUS,
                        "Пост связан с несколькими разными исходными публикациями"
                );
            }

            TelegramChannelPost nextPost =
                    relations.stream()
                            .map(
                                    TelegramChannelPostRelation
                                            ::getTargetPost
                            )
                            .filter(
                                    post ->
                                            post != null
                                                    && post.getId() != null
                            )
                            .findFirst()
                            .orElse(null);

            if (nextPost == null) {
                return new TelegramChannelPostRootResolution(
                        null,
                        TelegramChannelPostRootResolutionStatus.POST_MISSING,
                        "Связанный исходный пост отсутствует"
                );
            }

            currentPost = nextPost;
        }

        log.warn("Telegram channel post root resolution depth limit reached. initialPostId={}, maxDepth={}", initialPost.getId(), MAX_RELATION_DEPTH);

        return new TelegramChannelPostRootResolution(
                null,
                TelegramChannelPostRootResolutionStatus.DEPTH_LIMIT_REACHED,
                "Превышена допустимая глубина цепочки Telegram-постов"
        );
    }
}