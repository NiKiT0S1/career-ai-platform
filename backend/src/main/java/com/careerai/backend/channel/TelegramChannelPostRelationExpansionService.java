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
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.function.Predicate;

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
        // A current-looking reply may describe an expired event. Keep its original as
        // historical evidence; otherwise the reply becomes a phantom new event.
        return expand(searchResult, ChannelPostTimelineSearchService::allowedHistoricalContext);
    }

    /** Separate historical eligibility only for explicitly requested timeline context. */
    public ChannelPostSearchResult expand(ChannelPostSearchResult searchResult,
                                          Predicate<TelegramChannelPost> relatedEligibility) {
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
                collectConnectedRelations(basePostIds, relatedEligibility).stream()
                        .sorted(Comparator.comparing((TelegramChannelPostRelation relation) -> effectiveDate(relation.getSourcePost()),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .toList();

        if (foundRelations.isEmpty()) {
            boolean orphanReply = searchResult.allPosts().stream()
                    .anyMatch(post -> post.getReplyToTelegramMessageId() != null);
            return orphanReply ? new ChannelPostSearchResult(searchResult.groups(), searchResult.relations(), false)
                    : searchResult;
        }

        List<ChannelPostSearchGroup> expandedGroups =
                searchResult.groups()
                        .stream()
                        .map(group ->
                                expandGroup(
                                        group,
                                        foundRelations,
                                        relatedEligibility
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

        // A dropped older cancellation can remain effective after newer unrelated updates.
        // Never claim a chain is complete merely because its newest four posts fit the budget.
        boolean omittedEligibleRelation = foundRelations.stream().anyMatch(relation ->
                (!finalPostIds.contains(relation.getSourcePost().getId())
                        && relatedEligibility.test(relation.getSourcePost()))
                || (!finalPostIds.contains(relation.getTargetPost().getId())
                        && (relatedEligibility.test(relation.getTargetPost())
                            || finalPostIds.contains(relation.getSourcePost().getId()))));
        boolean missingReplyParent = resultWithoutRelations.allPosts().stream().anyMatch(post ->
                post.getReplyToTelegramMessageId() != null && foundRelations.stream().noneMatch(relation ->
                        relation.getSourcePost().getId().equals(post.getId())
                        && finalPostIds.contains(relation.getTargetPost().getId())));

        ChannelPostSearchResult expandedResult =
                new ChannelPostSearchResult(
                        expandedGroups,
                        includedRelations,
                        searchResult.relationContextComplete() && !omittedEligibleRelation && !missingReplyParent
                );

        int basePostCount =
                searchResult.allPosts().size();

        int finalPostCount =
                expandedResult.allPosts().size();

        log.info("Channel post relation expansion completed. basePosts={}, foundRelations={}, includedRelations={}, addedPosts={}, finalPosts={}, complete={}", basePostCount, foundRelations.size(), includedRelations.size(), finalPostCount - basePostCount, finalPostCount, expandedResult.relationContextComplete());

        return expandedResult;
    }

    private ChannelPostSearchGroup expandGroup(
            ChannelPostSearchGroup group,
            List<TelegramChannelPostRelation> relations,
            Predicate<TelegramChannelPost> relatedEligibility
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

        // Iterate because a reply to a reply can sort ahead of the edge to its root.
        for (int pass = 0; pass <= MAX_ADDITIONAL_POSTS_PER_GROUP; pass++) {
            int beforePass = additionalPosts;
            for (TelegramChannelPostRelation relation : relations) {
                if (additionalPosts >= MAX_ADDITIONAL_POSTS_PER_GROUP) break;
                TelegramChannelPost sourcePost = relation.getSourcePost();
                TelegramChannelPost targetPost = relation.getTargetPost();
                boolean containsSource = postsById.containsKey(sourcePost.getId());
                boolean containsTarget = postsById.containsKey(targetPost.getId());
                if (!containsSource && !containsTarget) continue;
                TelegramChannelPost relatedPost = containsSource ? targetPost : sourcePost;
                if (relatedEligibility.test(relatedPost) && !postsById.containsKey(relatedPost.getId())) {
                    postsById.put(relatedPost.getId(), relatedPost);
                    additionalPosts++;
                }
            }
            if (additionalPosts == beforePass || additionalPosts >= MAX_ADDITIONAL_POSTS_PER_GROUP) break;
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

    private List<TelegramChannelPostRelation> collectConnectedRelations(
            List<Long> baseIds, Predicate<TelegramChannelPost> eligibility) {
        Map<String, TelegramChannelPostRelation> found = new LinkedHashMap<>();
        Set<Long> visited = new LinkedHashSet<>();
        List<Long> frontier = baseIds;
        for (int depth = 0; depth <= MAX_ADDITIONAL_POSTS_PER_GROUP && !frontier.isEmpty(); depth++) {
            visited.addAll(frontier);
            Set<Long> next = new LinkedHashSet<>();
            for (TelegramChannelPostRelation relation : relationRepository.findConnectedToPostIds(frontier)) {
                if (!hasValidPosts(relation)) continue;
                found.putIfAbsent(relation.getSourcePost().getId() + ":" + relation.getTargetPost().getId(), relation);
                for (TelegramChannelPost post : List.of(relation.getSourcePost(), relation.getTargetPost())) {
                    if (!visited.contains(post.getId()) && eligibility.test(post)) next.add(post.getId());
                }
            }
            // Bound graph traversal as well as prompt size, preserving omitted-edge detection.
            frontier = next.stream().limit((long) Math.max(1, baseIds.size()) * MAX_ADDITIONAL_POSTS_PER_GROUP).toList();
        }
        return new ArrayList<>(found.values());
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
