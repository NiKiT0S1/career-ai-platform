package com.careerai.backend.channel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Итог гибридного поиска, разделённый по категориям.
 */

public record ChannelPostSearchResult(
        List<ChannelPostSearchGroup> groups
) {

    public ChannelPostSearchResult {
        groups = groups == null
                ? List.of()
                : List.copyOf(groups);
    }

    public static ChannelPostSearchResult empty() {
        return new ChannelPostSearchResult(List.of());
    }

    public boolean isEmpty() {
        return groups.stream()
                .allMatch(ChannelPostSearchGroup::isEmpty);
    }

    public List<TelegramChannelPost> allPosts() {
        Map<Long, TelegramChannelPost> uniquePosts =
                new LinkedHashMap<>();

        for (ChannelPostSearchGroup group : groups) {
            for (TelegramChannelPost post : group.posts()) {
                if (post != null && post.getId() != null) {
                    uniquePosts.putIfAbsent(
                            post.getId(),
                            post
                    );
                }
            }
        }

        return List.copyOf(uniquePosts.values());
    }
}