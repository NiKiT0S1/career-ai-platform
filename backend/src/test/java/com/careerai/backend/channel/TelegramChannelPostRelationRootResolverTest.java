package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramChannelPostRelationRootResolverTest {

    @Mock
    private TelegramChannelPostRelationRepository
            relationRepository;

    @Test
    void returnsSamePostWhenItHasNoParentRelation() {
        TelegramChannelPost post =
                createPost(100L);

        when(
                relationRepository
                        .findAllBySourcePostIdWithTarget(
                                100L
                        )
        ).thenReturn(List.of());

        TelegramChannelPostRelationRootResolver resolver =
                new TelegramChannelPostRelationRootResolver(
                        relationRepository
                );

        TelegramChannelPostRootResolution result =
                resolver.resolveRoot(post);

        assertTrue(result.resolved());
        assertEquals(
                100L,
                result.rootPost().getId()
        );
    }

    @Test
    void resolvesNestedRelationToRoot() {
        TelegramChannelPost root =
                createPost(100L);

        TelegramChannelPost middle =
                createPost(101L);

        TelegramChannelPostRelation relation =
                new TelegramChannelPostRelation();

        relation.setId(1L);
        relation.setSourcePost(middle);
        relation.setTargetPost(root);
        relation.setRelationType(
                TelegramChannelPostRelationType.UPDATE
        );
        relation.setCreatedAt(
                OffsetDateTime.now()
        );

        when(
                relationRepository
                        .findAllBySourcePostIdWithTarget(
                                101L
                        )
        ).thenReturn(List.of(relation));

        when(
                relationRepository
                        .findAllBySourcePostIdWithTarget(
                                100L
                        )
        ).thenReturn(List.of());

        TelegramChannelPostRelationRootResolver resolver =
                new TelegramChannelPostRelationRootResolver(
                        relationRepository
                );

        TelegramChannelPostRootResolution result =
                resolver.resolveRoot(middle);

        assertTrue(result.resolved());
        assertEquals(
                100L,
                result.rootPost().getId()
        );
    }

    private TelegramChannelPost createPost(
            Long id
    ) {
        TelegramChannelPost post =
                new TelegramChannelPost();

        post.setId(id);
        post.setTelegramChatId(
                -1001234567890L
        );
        post.setTelegramMessageId(id);
        post.setText(
                "Тестовый пост " + id
        );

        return post;
    }
}