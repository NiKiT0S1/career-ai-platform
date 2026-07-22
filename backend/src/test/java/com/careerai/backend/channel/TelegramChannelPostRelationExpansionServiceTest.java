package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramChannelPostRelationExpansionServiceTest {

    private static final long SOURCE_POST_ID = 15L;
    private static final long TARGET_POST_ID = 10L;

    @Mock
    private TelegramChannelPostRelationRepository
            relationRepository;

    @Mock
    private TelegramChannelPostSearchEligibility searchEligibility;

    @Test
    void addsSearchableRelatedPost() {
        TelegramChannelPost sourcePost =
                createPost(
                        SOURCE_POST_ID,
                        OffsetDateTime.parse(
                                "2026-07-17T10:00:00+05:00"
                        ),
                        false
                );

        TelegramChannelPost targetPost =
                createPost(
                        TARGET_POST_ID,
                        OffsetDateTime.parse(
                                "2026-07-16T10:00:00+05:00"
                        ),
                        false
                );

        TelegramChannelPostRelation relation =
                createRelation(
                        sourcePost,
                        targetPost
                );

        when(
                relationRepository
                        .findConnectedToPostIds(
                                List.of(TARGET_POST_ID)
                        )
        ).thenReturn(List.of(relation));

        when(searchEligibility.isSearchable(sourcePost)).thenReturn(true);

        TelegramChannelPostRelationExpansionService service =
                new TelegramChannelPostRelationExpansionService(
                        relationRepository,
                        searchEligibility
                );

        ChannelPostSearchResult initialResult =
                new ChannelPostSearchResult(
                        List.of(
                                new ChannelPostSearchGroup(
                                        ChannelContentScope.EVENTS,
                                        List.of(targetPost)
                                )
                        )
                );

        ChannelPostSearchResult result =
                service.expand(initialResult);

        assertEquals(
                List.of(
                        SOURCE_POST_ID,
                        TARGET_POST_ID
                ),
                result.groups()
                        .getFirst()
                        .posts()
                        .stream()
                        .map(TelegramChannelPost::getId)
                        .toList()
        );

        assertEquals(
                1,
                result.relations().size()
        );
    }

    @Test
    void doesNotAddArchivedRelatedPost() {
        TelegramChannelPost sourcePost =
                createPost(
                        SOURCE_POST_ID,
                        OffsetDateTime.parse(
                                "2026-07-17T10:00:00+05:00"
                        ),
                        true
                );

        TelegramChannelPost targetPost =
                createPost(
                        TARGET_POST_ID,
                        OffsetDateTime.parse(
                                "2026-07-16T10:00:00+05:00"
                        ),
                        false
                );

        TelegramChannelPostRelation relation =
                createRelation(
                        sourcePost,
                        targetPost
                );

        when(
                relationRepository
                        .findConnectedToPostIds(
                                List.of(TARGET_POST_ID)
                        )
        ).thenReturn(List.of(relation));

        when(searchEligibility.isSearchable(sourcePost)).thenReturn(false);

        TelegramChannelPostRelationExpansionService service =
                new TelegramChannelPostRelationExpansionService(
                        relationRepository,
                        searchEligibility
                );

        ChannelPostSearchResult initialResult =
                new ChannelPostSearchResult(
                        List.of(
                                new ChannelPostSearchGroup(
                                        ChannelContentScope.EVENTS,
                                        List.of(targetPost)
                                )
                        )
                );

        ChannelPostSearchResult result =
                service.expand(initialResult);

        assertEquals(
                List.of(TARGET_POST_ID),
                result.groups()
                        .getFirst()
                        .posts()
                        .stream()
                        .map(TelegramChannelPost::getId)
                        .toList()
        );

        assertEquals(
                0,
                result.relations().size()
        );
    }

    @Test
    void doesNotQueryRelationsForEmptyResult() {
        TelegramChannelPostRelationExpansionService service =
                new TelegramChannelPostRelationExpansionService(
                        relationRepository,
                        searchEligibility
                );

        ChannelPostSearchResult result =
                service.expand(
                        ChannelPostSearchResult.empty()
                );

        assertEquals(
                0,
                result.allPosts().size()
        );

        verifyNoInteractions(relationRepository, searchEligibility);
    }

    private TelegramChannelPost createPost(
            long id,
            OffsetDateTime postedAt,
            boolean archived
    ) {
        TelegramChannelPost post =
                new TelegramChannelPost();

        post.setId(id);
        post.setTelegramChatId(-1001234567890L);
        post.setTelegramMessageId(id);
        post.setText("Тестовый текст поста " + id);
        post.setPostedAt(postedAt);
        post.setFreshnessStatus(
                TelegramChannelPostFreshnessStatus.UNKNOWN
        );
        post.setArchived(archived);

        return post;
    }

    private TelegramChannelPostRelation createRelation(
            TelegramChannelPost sourcePost,
            TelegramChannelPost targetPost
    ) {
        TelegramChannelPostRelation relation =
                new TelegramChannelPostRelation();

        relation.setId(100L);
        relation.setSourcePost(sourcePost);
        relation.setTargetPost(targetPost);
        relation.setRelationType(
                TelegramChannelPostRelationType.CANCELLATION
        );
        relation.setReason(
                "Отменена раздача напитков"
        );
        relation.setCreatedAt(
                OffsetDateTime.parse(
                        "2026-07-17T12:00:00+05:00"
                )
        );

        return relation;
    }
}