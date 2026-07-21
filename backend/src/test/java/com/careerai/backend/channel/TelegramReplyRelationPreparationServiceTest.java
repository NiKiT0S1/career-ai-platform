package com.careerai.backend.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramReplyRelationPreparationServiceTest {

    @Mock
    private TelegramChannelPostRepository
            postRepository;

    @Mock
    private TelegramChannelPostRelationRepository
            relationRepository;

    @Mock
    private TelegramChannelPostRelationRootResolver
            rootResolver;

    @Mock
    private TelegramRelationClassificationInputHashService
            inputHashService;

    private TelegramReplyRelationPreparationService
            preparationService;

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

        preparationService =
                new TelegramReplyRelationPreparationService(
                        postRepository,
                        relationRepository,
                        rootResolver,
                        inputHashService,
                        clock
                );
    }

    @Test
    void createsPendingRelationForReplyPost() {
        TelegramChannelPost source =
                createPost(
                        20L,
                        20L
                );

        source.setReplyToTelegramMessageId(
                10L
        );

        TelegramChannelPost target =
                createPost(
                        10L,
                        10L
                );

        when(
                postRepository.findById(
                        20L
                )
        ).thenReturn(
                Optional.of(source)
        );

        when(
                postRepository
                        .findByTelegramChatIdAndTelegramMessageId(
                                source.getTelegramChatId(),
                                10L
                        )
        ).thenReturn(
                Optional.of(target)
        );

        when(
                rootResolver.resolveRoot(
                        target
                )
        ).thenReturn(
                new TelegramChannelPostRootResolution(
                        target,
                        TelegramChannelPostRootResolutionStatus
                                .RESOLVED,
                        "Корень найден"
                )
        );

        when(
                relationRepository
                        .findAllBySourcePostIdWithTarget(
                                20L
                        )
        ).thenReturn(List.of());

        when(
                inputHashService.calculate(
                        any(),
                        eq(
                                TelegramRelationClassifierVersion
                                        .CURRENT
                        )
                )
        ).thenReturn("test-hash");

        when(
                relationRepository
                        .findBySourcePostIdAndTargetPostId(
                                20L,
                                10L
                        )
        ).thenReturn(Optional.empty());

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

        Optional<Long> result =
                preparationService.prepare(
                        20L,
                        TelegramChannelPostRelationOrigin
                                .TELEGRAM_REPLY
                );

        assertEquals(
                Optional.of(100L),
                result
        );

        ArgumentCaptor<TelegramChannelPostRelation>
                captor =
                ArgumentCaptor.forClass(
                        TelegramChannelPostRelation.class
                );

        verify(relationRepository)
                .save(
                        captor.capture()
                );

        TelegramChannelPostRelation saved =
                captor.getValue();

        assertEquals(
                TelegramChannelPostRelationType
                        .UNCLASSIFIED,
                saved.getRelationType()
        );

        assertEquals(
                TelegramChannelPostRelationClassificationStatus
                        .PENDING,
                saved.getClassificationStatus()
        );

        assertEquals(
                TelegramChannelPostRelationOrigin
                        .TELEGRAM_REPLY,
                saved.getRelationOrigin()
        );

        assertEquals(
                "test-hash",
                saved.getClassificationInputHash()
        );
    }

    @Test
    void skipsPostWithoutReplyReference() {
        TelegramChannelPost source =
                createPost(
                        20L,
                        20L
                );

        when(
                postRepository.findById(
                        20L
                )
        ).thenReturn(
                Optional.of(source)
        );

        Optional<Long> result =
                preparationService.prepare(
                        20L,
                        TelegramChannelPostRelationOrigin
                                .TELEGRAM_REPLY
                );

        assertTrue(result.isEmpty());

        verifyNoInteractions(
                relationRepository,
                rootResolver,
                inputHashService
        );
    }

    private TelegramChannelPost createPost(
            Long id,
            Long messageId
    ) {
        TelegramChannelPost post =
                new TelegramChannelPost();

        post.setId(id);
        post.setTelegramChatId(
                -1001234567890L
        );
        post.setTelegramMessageId(
                messageId
        );
        post.setText(
                "Тестовый пост " + id
        );
        post.setPostedAt(
                OffsetDateTime.parse(
                        "2026-07-17T10:00:00+05:00"
                )
        );

        return post;
    }
}