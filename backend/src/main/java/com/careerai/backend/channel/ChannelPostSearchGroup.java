package com.careerai.backend.channel;

import java.util.List;

/**
 * Публикации, найденные для одной категории запроса.
 */

public record ChannelPostSearchGroup(
        ChannelContentScope scope,
        List<TelegramChannelPost> posts
) {

    public ChannelPostSearchGroup {
        scope = scope == null
                ? ChannelContentScope.NONE
                : scope;

        posts = posts == null
                ? List.of()
                : List.copyOf(posts);
    }

    public boolean isEmpty() {
        return posts.isEmpty();
    }
}