package com.careerai.backend.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramChannelPostRelationServiceTest {

    private static final long SOURCE_POST_ID = 15L;
    private static final long TARGET_POST_ID = 10L;

    @Mock
    private TelegramChannelPostRelationRepository
            relationRepository;

    @Mock
    private TelegramChannelPostRepository
            postRepository;

    private TelegramChannelPostRelationService
            relationService;

    @BeforeEach
    void setUp() {
        Clock clock =
                Clock.fixed(
                        Instant.parse(
                                "2026-07-17T12:00:00Z"
                        ),
                        ZoneId.of(
                                "Asia/Almaty"
                        )
                );

        relationService =
                new TelegramChannelPostRelationService(
                        relationRepository,
                        postRepository,
                        clock
                );
    }

    @Test
    void createsManualRelation() {
        TelegramChannelPost source =
                createPost(SOURCE_POST_ID);

        TelegramChannelPost target =
                createPost(TARGET_POST_ID);

        when(
                relationRepository
                        .findBySourcePostIdAndTargetPostId(
                                SOURCE_POST_ID,
                                TARGET_POST_ID
                        )
        ).thenReturn(Optional.empty());

        when(
                postRepository.findById(
                        SOURCE_POST_ID
                )
        ).thenReturn(Optional.of(source));

        when(
                postRepository.findById(
                        TARGET_POST_ID
                )
        ).thenReturn(Optional.of(target));

        when(
                relationRepository.save(
                        any()
                )
        ).thenAnswer(
                invocation -> {
                    TelegramChannelPostRelation relation =
                            invocation.getArgument(0);

                    relation.setId(100L);

                    return relation;
                }
        );

        TelegramChannelPostRelationResult result =
                relationService.link(
                        SOURCE_POST_ID,
                        TARGET_POST_ID,
                        TelegramChannelPostRelationType
                                .CANCELLATION,
                        "Отменено отдельное условие"
                );

        assertTrue(result.linked());
        assertTrue(result.changed());

        verify(relationRepository)
                .save(any());
    }

    @Test
    void updatesExistingAutomaticRelationManually() {
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
                TelegramChannelPostRelationType
                        .UNCLASSIFIED
        );
        relation.setRelationOrigin(
                TelegramChannelPostRelationOrigin
                        .TELEGRAM_REPLY
        );
        relation.setClassificationStatus(
                TelegramChannelPostRelationClassificationStatus
                        .REVIEW_REQUIRED
        );

        when(
                relationRepository
                        .findBySourcePostIdAndTargetPostId(
                                SOURCE_POST_ID,
                                TARGET_POST_ID
                        )
        ).thenReturn(
                Optional.of(relation)
        );

        TelegramChannelPostRelationResult result =
                relationService.link(
                        SOURCE_POST_ID,
                        TARGET_POST_ID,
                        TelegramChannelPostRelationType
                                .UPDATE,
                        "Добавлены новые сведения"
                );

        assertTrue(result.changed());

        assertEquals(
                TelegramChannelPostRelationOrigin
                        .MANUAL,
                relation.getRelationOrigin()
        );

        assertEquals(
                TelegramChannelPostRelationClassificationStatus
                        .NOT_REQUIRED,
                relation.getClassificationStatus()
        );

        verify(relationRepository)
                .save(relation);
    }

    private TelegramChannelPost createPost(
            long id
    ) {
        TelegramChannelPost post =
                new TelegramChannelPost();

        post.setId(id);
        post.setTelegramChatId(
                -1001234567890L
        );
        post.setTelegramMessageId(id);

        return post;
    }
}