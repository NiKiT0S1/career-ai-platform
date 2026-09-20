package com.careerai.backend.channel;

import java.util.*;

/** Keeps complete chains when combining ordinary search with historical deadline evidence. */
final class ChannelPostContextMerger {
    private ChannelPostContextMerger() { }

    static ChannelPostSearchResult merge(ChannelPostSearchResult current, ChannelPostSearchResult historical) {
        if (historical == null || (historical.isEmpty() && historical.relationContextComplete())) return current;
        Map<ChannelContentScope, LinkedHashMap<Long, TelegramChannelPost>> groups = new LinkedHashMap<>();
        Map<String, TelegramChannelPostRelation> relations = new LinkedHashMap<>();
        for (ChannelPostSearchResult result : List.of(current, historical)) {
            for (ChannelPostSearchGroup group : result.groups()) {
                var posts = groups.computeIfAbsent(group.scope(), ignored -> new LinkedHashMap<>());
                for (TelegramChannelPost post : group.posts()) {
                    if (post != null && post.getId() != null) posts.putIfAbsent(post.getId(), post);
                }
            }
            for (TelegramChannelPostRelation relation : result.relations()) {
                if (relation.getSourcePost() == null || relation.getTargetPost() == null) continue;
                String key = relation.getSourcePost().getId() + ":" + relation.getTargetPost().getId()
                        + ":" + relation.getRelationType();
                relations.putIfAbsent(key, relation);
            }
        }
        var combined = new ChannelPostSearchResult(groups.entrySet().stream()
                .map(entry -> new ChannelPostSearchGroup(entry.getKey(), List.copyOf(entry.getValue().values())))
                .toList(), List.copyOf(relations.values()),
                current.relationContextComplete() && historical.relationContextComplete());
        // Do not silently truncate corrections or cancellations to meet the prompt budget.
        if (combined.allPosts().size() > 60) {
            return new ChannelPostSearchResult(combined.groups(), combined.relations(), false);
        }
        return combined;
    }
}
