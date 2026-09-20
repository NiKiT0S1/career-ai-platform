package com.careerai.backend.channel;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ChannelPostTimelineSearchService {
    private final ChannelPostTimelineRepository repository;
    private final ChannelQueryWindowResolver windows;
    private final TelegramChannelPostRelationExpansionService relations;
    private final Clock clock;

    public ChannelPostTimelineSearchService(ChannelPostTimelineRepository repository,
                                           ChannelQueryWindowResolver windows,
                                           TelegramChannelPostRelationExpansionService relations, Clock clock) {
        this.repository = repository;
        this.windows = windows;
        this.relations = relations;
        this.clock = clock;
    }

    public ChannelPostSearchResult search(ChannelQueryAnalysis analysis, int totalLimit) {
        Optional<ChannelQueryWindowResolver.Window> window = windows.resolve(analysis);
        if (window.isEmpty() || !analysis.needsChannelPosts()) return ChannelPostSearchResult.empty();
        List<ChannelContentScope> scopes = analysis.contentScopes().stream()
                .filter(scope -> scope != ChannelContentScope.NONE).toList();
        List<ChannelPostSearchGroup> groups = new ArrayList<>();
        int remaining = Math.max(1, totalLimit);
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (int index = 0; index < scopes.size() && remaining > 0; index++) {
            ChannelContentScope scope = scopes.get(index);
            int limit = Math.max(1, remaining / (scopes.size() - index));
            List<TelegramChannelPost> posts = repository.findInWindow(types(scope), scope == ChannelContentScope.PRACTICE,
                    analysis.freshnessScope().name(), window.get().fromInclusive(), window.get().toExclusive(), now,
                    PageRequest.of(0, limit)).stream()
                    // Keep safety at the service boundary as well as in SQL.
                    .filter(post -> matches(post, analysis.freshnessScope(), window.get(), now)).limit(limit).toList();
            groups.add(new ChannelPostSearchGroup(scope, posts));
            remaining -= posts.size();
        }
        // Related corrections may lie outside the requested publication window. They are context,
        // not additional matching announcements; all are labelled separately in the RAG prompt.
        return relations.expand(new ChannelPostSearchResult(groups), ChannelPostTimelineSearchService::allowedHistoricalContext);
    }

    /** Supplement a deadline question, without turning expired opportunities into current offers. */
    public ChannelPostSearchResult searchDeadlineKnowledge(ChannelQueryAnalysis analysis, int totalLimit) {
        Optional<ChannelQueryWindowResolver.Window> window = windows.resolve(analysis);
        if (window.isEmpty() || !analysis.needsChannelPosts()) return ChannelPostSearchResult.empty();
        List<ChannelContentScope> scopes = analysis.contentScopes().stream()
                .filter(scope -> scope != ChannelContentScope.NONE).toList();
        List<ChannelPostSearchGroup> groups = new ArrayList<>();
        int remaining = Math.max(1, totalLimit);
        for (int index = 0; index < scopes.size() && remaining > 0; index++) {
            ChannelContentScope scope = scopes.get(index);
            int limit = Math.max(1, remaining / (scopes.size() - index));
            // A generic document-extension notice can be classified DEADLINE rather than PRACTICE.
            List<TelegramChannelPostType> deadlineTypes = scope == ChannelContentScope.PRACTICE
                    ? List.of(TelegramChannelPostType.PRACTICE, TelegramChannelPostType.DEADLINE) : types(scope);
            List<TelegramChannelPost> posts = repository.findDeadlineKnowledge(deadlineTypes,
                    scope == ChannelContentScope.PRACTICE, window.get().fromInclusive(), window.get().toExclusive(),
                    PageRequest.of(0, limit)).stream()
                    .filter(ChannelPostTimelineSearchService::allowedHistoricalContext)
                    .filter(post -> window.get().contains(publicationDate(post))).limit(limit).toList();
            groups.add(new ChannelPostSearchGroup(scope, posts));
            remaining -= posts.size();
        }
        return relations.expand(new ChannelPostSearchResult(groups), ChannelPostTimelineSearchService::allowedHistoricalContext);
    }

    static boolean matches(TelegramChannelPost post, ChannelFreshnessScope freshness,
                           ChannelQueryWindowResolver.Window window, OffsetDateTime now) {
        if (!allowedHistoricalContext(post) || !window.contains(publicationDate(post))) return false;
        boolean expired = post.getFreshnessStatus() == TelegramChannelPostFreshnessStatus.EXPIRED
                || (post.getExpiresAt() != null && !post.getExpiresAt().isAfter(now));
        return freshness == ChannelFreshnessScope.ALL
                || (freshness == ChannelFreshnessScope.EXPIRED ? expired : !expired);
    }

    static boolean allowedHistoricalContext(TelegramChannelPost post) {
        return post != null && !post.isArchived() && post.getText() != null && !post.getText().isBlank()
                && (post.getFreshnessStatus() == TelegramChannelPostFreshnessStatus.ACTIVE
                || post.getFreshnessStatus() == TelegramChannelPostFreshnessStatus.UNKNOWN
                || post.getFreshnessStatus() == TelegramChannelPostFreshnessStatus.EXPIRED);
    }

    static OffsetDateTime publicationDate(TelegramChannelPost post) {
        return post.getPostedAt() != null ? post.getPostedAt() : post.getCreatedAt();
    }

    private List<TelegramChannelPostType> types(ChannelContentScope scope) {
        return switch (scope) {
            case VACANCIES -> List.of(TelegramChannelPostType.VACANCY);
            case EVENTS -> List.of(TelegramChannelPostType.EVENT, TelegramChannelPostType.ANNOUNCEMENT);
            case PRACTICE -> List.of(TelegramChannelPostType.PRACTICE);
            case DEADLINES -> List.of(TelegramChannelPostType.DEADLINE, TelegramChannelPostType.PRACTICE,
                    TelegramChannelPostType.EVENT, TelegramChannelPostType.VACANCY);
            case ALL_UPDATES -> List.of(TelegramChannelPostType.values());
            case NONE -> List.of();
        };
    }
}
