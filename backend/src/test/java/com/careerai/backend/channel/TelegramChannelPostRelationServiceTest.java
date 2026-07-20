package com.careerai.backend.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramChannelPostRelationServiceTest {

    private static final long SOURCE_POST_ID = 15L;
    private static final long TARGET_POST_ID = 10L;

    private static final Instant CURRENT_INSTANT =
            Instant.parse("2026-07-17T12:00:00Z");

    private static final ZoneId APPLICATION_ZONE =
            ZoneId.of("Asia/Almaty");

    @Mock
    private TelegramChannelPostRelationRepository relationRepository;

    @Mock
    private TelegramChannelPostRepository postRepository;

    private TelegramChannelPostRelationService relationService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                CURRENT_INSTANT,
                APPLICATION_ZONE
        );

        relationService =
                new TelegramChannelPostRelationService(
                        relationRepository,
                        postRepository,
                        clock
                );
    }

    @Test
    void createsRelation() {
        TelegramChannelPost sourcePost =
                createPost(SOURCE_POST_ID);

        TelegramChannelPost targetPost =
                createPost(TARGET_POST_ID);

        when(
                relationRepository
                        .findBySourcePostIdAndTargetPostIdAndRelationType(
                                SOURCE_POST_ID,
                                TARGET_POST_ID,
                                TelegramChannelPostRelationType.CANCELLATION
                        )
        ).thenReturn(Optional.empty());

        when(postRepository.findById(SOURCE_POST_ID))
                .thenReturn(Optional.of(sourcePost));

        when(postRepository.findById(TARGET_POST_ID))
                .thenReturn(Optional.of(targetPost));

        when(relationRepository.save(any()))
                .thenAnswer(invocation -> {
                    TelegramChannelPostRelation relation =
                            invocation.getArgument(0);

                    relation.setId(100L);

                    return relation;
                });

        TelegramChannelPostRelationResult result =
                relationService.link(
                        SOURCE_POST_ID,
                        TARGET_POST_ID,
                        TelegramChannelPostRelationType.CANCELLATION,
                        "  Отменена раздача напитков  "
                );

        assertTrue(result.linked());
        assertTrue(result.changed());
        assertEquals(100L, result.relationId());
        assertEquals(
                "Отменена раздача напитков",
                result.reason()
        );

        assertEquals(
                OffsetDateTime.ofInstant(
                        CURRENT_INSTANT,
                        APPLICATION_ZONE
                ),
                result.createdAt()
        );

        verify(relationRepository).save(any());
    }

    @Test
    void doesNotCreateDuplicateRelation() {
        TelegramChannelPostRelation relation =
                createRelation();

        when(
                relationRepository
                        .findBySourcePostIdAndTargetPostIdAndRelationType(
                                SOURCE_POST_ID,
                                TARGET_POST_ID,
                                TelegramChannelPostRelationType.CANCELLATION
                        )
        ).thenReturn(Optional.of(relation));

        TelegramChannelPostRelationResult result =
                relationService.link(
                        SOURCE_POST_ID,
                        TARGET_POST_ID,
                        TelegramChannelPostRelationType.CANCELLATION,
                        "Другая причина"
                );

        assertTrue(result.linked());
        assertFalse(result.changed());

        verifyNoInteractions(postRepository);
        verify(relationRepository, never())
                .save(any());
    }

    @Test
    void deletesRelation() {
        TelegramChannelPostRelation relation =
                createRelation();

        when(
                relationRepository
                        .findBySourcePostIdAndTargetPostIdAndRelationType(
                                SOURCE_POST_ID,
                                TARGET_POST_ID,
                                TelegramChannelPostRelationType.CANCELLATION
                        )
        ).thenReturn(Optional.of(relation));

        TelegramChannelPostRelationResult result =
                relationService.unlink(
                        SOURCE_POST_ID,
                        TARGET_POST_ID,
                        TelegramChannelPostRelationType.CANCELLATION
                );

        assertFalse(result.linked());
        assertTrue(result.changed());

        verify(relationRepository)
                .delete(relation);
    }

    @Test
    void doesNotDeleteMissingRelation() {
        when(
                relationRepository
                        .findBySourcePostIdAndTargetPostIdAndRelationType(
                                SOURCE_POST_ID,
                                TARGET_POST_ID,
                                TelegramChannelPostRelationType.CORRECTION
                        )
        ).thenReturn(Optional.empty());

        TelegramChannelPostRelationResult result =
                relationService.unlink(
                        SOURCE_POST_ID,
                        TARGET_POST_ID,
                        TelegramChannelPostRelationType.CORRECTION
                );

        assertFalse(result.linked());
        assertFalse(result.changed());

        verify(relationRepository, never())
                .delete(any());
    }

    @Test
    void rejectsSelfRelation() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                relationService.link(
                                        SOURCE_POST_ID,
                                        SOURCE_POST_ID,
                                        TelegramChannelPostRelationType.UPDATE,
                                        "Обновление"
                                )
                );

        assertEquals(
                "Telegram-пост нельзя связать с самим собой",
                exception.getMessage()
        );

        verifyNoInteractions(
                relationRepository,
                postRepository
        );
    }

    @Test
    void rejectsBlankReason() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        relationService.link(
                                SOURCE_POST_ID,
                                TARGET_POST_ID,
                                TelegramChannelPostRelationType.UPDATE,
                                "   "
                        )
        );

        verifyNoInteractions(
                relationRepository,
                postRepository
        );
    }

    private TelegramChannelPost createPost(long id) {
        TelegramChannelPost post =
                new TelegramChannelPost();

        post.setId(id);
        post.setTelegramChatId(-1001234567890L);
        post.setTelegramMessageId(id);

        return post;
    }

    private TelegramChannelPostRelation createRelation() {
        TelegramChannelPostRelation relation =
                new TelegramChannelPostRelation();

        relation.setId(100L);
        relation.setSourcePost(
                createPost(SOURCE_POST_ID)
        );
        relation.setTargetPost(
                createPost(TARGET_POST_ID)
        );
        relation.setRelationType(
                TelegramChannelPostRelationType.CANCELLATION
        );
        relation.setReason(
                "Отменена раздача напитков"
        );
        relation.setCreatedAt(
                OffsetDateTime.ofInstant(
                        CURRENT_INSTANT,
                        APPLICATION_ZONE
                )
        );

        return relation;
    }
}