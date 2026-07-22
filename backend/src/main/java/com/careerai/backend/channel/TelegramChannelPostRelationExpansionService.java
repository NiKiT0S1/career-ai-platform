package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Дополняет результаты поиска явно связанными
 * исправлениями, обновлениями и отменами.
 */

@Service
public class TelegramChannelPostRelationExpansionService {

    /**
     * Защищает RAG-контекст от неограниченного роста,
     * если у публикации окажется слишком много связей.
     */
    private static final int MAX_ADDITIONAL_POSTS_PER_GROUP = 4;

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelPostRelationExpansionService.class);

    private final TelegramChannelPostRelationRepository relationRepository;

    private final TelegramChannelPostSearchEligibility searchEligibility;

    public TelegramChannelPostRelationExpansionService(
            TelegramChannelPostRelationRepository relationRepository,
            TelegramChannelPostSearchEligibility searchEligibility
    ) {
        this.relationRepository = relationRepository;
        this.searchEligibility = searchEligibility;
    }

    /**
     * Добавляет к найденным постам связанные публикации.
     *
     * Например, если поиск нашёл исходное мероприятие,
     * сервис добавит более новый пост с его отменой.
     */
    public ChannelPostSearchResult expand(
            ChannelPostSearchResult searchResult
    ) {
        if (searchResult == null) {
            return ChannelPostSearchResult.empty();
        }

        if (searchResult.isEmpty()) {
            return searchResult;
        }

        List<Long> basePostIds = searchResult.allPosts()
                .stream()
                .map(TelegramChannelPost::getId)
                .filter(id -> id != null)
                .toList();

        if (basePostIds.isEmpty()) {
            return searchResult;
        }

        List<TelegramChannelPostRelation> foundRelations =
                relationRepository
                        .findConnectedToPostIds(basePostIds)
                        .stream()
                        .filter(this::hasValidPosts)
                        .toList();

        if (foundRelations.isEmpty()) {
            return searchResult;
        }

        List<ChannelPostSearchGroup> expandedGroups =
                searchResult.groups()
                        .stream()
                        .map(group ->
                                expandGroup(
                                        group,
                                        foundRelations
                                )
                        )
                        .toList();

        ChannelPostSearchResult resultWithoutRelations =
                new ChannelPostSearchResult(
                        expandedGroups,
                        List.of()
                );

        Set<Long> finalPostIds =
                resultWithoutRelations
                        .allPosts()
                        .stream()
                        .map(TelegramChannelPost::getId)
                        .filter(id -> id != null)
                        .collect(Collectors.toSet());

        /*
         * В prompt передаём только те связи,
         * для которых присутствуют оба поста.
         */
        List<TelegramChannelPostRelation> includedRelations =
                foundRelations.stream()
                        .filter(relation ->
                                finalPostIds.contains(
                                        relation
                                                .getSourcePost()
                                                .getId()
                                )
                                        && finalPostIds.contains(
                                        relation
                                                .getTargetPost()
                                                .getId()
                                )
                        )
                        .toList();

        ChannelPostSearchResult expandedResult =
                new ChannelPostSearchResult(
                        expandedGroups,
                        includedRelations
                );

        int basePostCount =
                searchResult.allPosts().size();

        int finalPostCount =
                expandedResult.allPosts().size();

        log.info("Channel post relation expansion completed. basePosts={}, foundRelations={}, includedRelations={}, addedPosts={}, finalPosts={}", basePostCount, foundRelations.size(), includedRelations.size(), finalPostCount - basePostCount, finalPostCount);

        return expandedResult;
    }

    private ChannelPostSearchGroup expandGroup(
            ChannelPostSearchGroup group,
            List<TelegramChannelPostRelation> relations
    ) {
        Map<Long, TelegramChannelPost> postsById =
                new LinkedHashMap<>();

        for (TelegramChannelPost post : group.posts()) {
            if (post != null && post.getId() != null) {
                postsById.putIfAbsent(
                        post.getId(),
                        post
                );
            }
        }

        int additionalPosts = 0;

        for (TelegramChannelPostRelation relation : relations) {
            if (additionalPosts
                    >= MAX_ADDITIONAL_POSTS_PER_GROUP) {
                break;
            }

            TelegramChannelPost sourcePost =
                    relation.getSourcePost();

            TelegramChannelPost targetPost =
                    relation.getTargetPost();

            boolean containsSource =
                    postsById.containsKey(
                            sourcePost.getId()
                    );

            boolean containsTarget =
                    postsById.containsKey(
                            targetPost.getId()
                    );

            if (!containsSource && !containsTarget) {
                continue;
            }

            TelegramChannelPost relatedPost =
                    containsSource
                            ? targetPost
                            : sourcePost;

            if (!searchEligibility.isSearchable(relatedPost)) {
                continue;
            }

            if (!postsById.containsKey(
                    relatedPost.getId()
            )) {
                postsById.put(
                        relatedPost.getId(),
                        relatedPost
                );

                additionalPosts++;
            }
        }

        List<TelegramChannelPost> orderedPosts =
                postsById.values()
                        .stream()
                        .sorted(
                                Comparator.comparing(
                                        this::effectiveDate,
                                        Comparator.nullsLast(
                                                Comparator.reverseOrder()
                                        )
                                )
                        )
                        .toList();

        return new ChannelPostSearchGroup(
                group.scope(),
                orderedPosts
        );
    }

    private boolean hasValidPosts(
            TelegramChannelPostRelation relation
    ) {
        return relation != null
                && relation.getSourcePost() != null
                && relation.getSourcePost().getId() != null
                && relation.getTargetPost() != null
                && relation.getTargetPost().getId() != null;
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
}