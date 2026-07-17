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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramChannelPostArchiveServiceTest {

    private static final long POST_ID = 13L;

    private static final Instant CURRENT_INSTANT =
            Instant.parse("2026-07-17T12:00:00Z");

    private static final ZoneId APPLICATION_ZONE =
            ZoneId.of("Asia/Almaty");

    @Mock
    private TelegramChannelPostRepository postRepository;

    private TelegramChannelPostArchiveService archiveService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                CURRENT_INSTANT,
                APPLICATION_ZONE
        );

        archiveService =
                new TelegramChannelPostArchiveService(
                        postRepository,
                        clock
                );
    }

    @Test
    void archivesPost() {
        TelegramChannelPost post =
                createPost(false);

        when(postRepository.findById(POST_ID))
                .thenReturn(Optional.of(post));

        TelegramChannelPostArchiveResult result =
                archiveService.archive(
                        POST_ID,
                        "  Вакансия закрыта  "
                );

        OffsetDateTime expectedArchivedAt =
                OffsetDateTime.ofInstant(
                        CURRENT_INSTANT,
                        APPLICATION_ZONE
                );

        assertTrue(result.changed());
        assertTrue(result.archived());
        assertEquals(
                expectedArchivedAt,
                result.archivedAt()
        );
        assertEquals(
                "Вакансия закрыта",
                result.archiveReason()
        );

        assertTrue(post.isArchived());
        assertEquals(
                expectedArchivedAt,
                post.getArchivedAt()
        );
        assertEquals(
                "Вакансия закрыта",
                post.getArchiveReason()
        );

        verify(postRepository).save(post);
    }

    @Test
    void doesNotArchivePostTwice() {
        TelegramChannelPost post =
                createPost(true);

        OffsetDateTime originalArchivedAt =
                OffsetDateTime.parse(
                        "2026-07-16T10:00:00+05:00"
                );

        post.setArchivedAt(originalArchivedAt);
        post.setArchiveReason("Первая причина");

        when(postRepository.findById(POST_ID))
                .thenReturn(Optional.of(post));

        TelegramChannelPostArchiveResult result =
                archiveService.archive(
                        POST_ID,
                        "Новая причина"
                );

        assertFalse(result.changed());
        assertTrue(result.archived());
        assertEquals(
                originalArchivedAt,
                result.archivedAt()
        );
        assertEquals(
                "Первая причина",
                result.archiveReason()
        );

        verify(postRepository, never())
                .save(any());
    }

    @Test
    void restoresArchivedPost() {
        TelegramChannelPost post =
                createPost(true);

        post.setArchivedAt(
                OffsetDateTime.parse(
                        "2026-07-16T10:00:00+05:00"
                )
        );
        post.setArchiveReason("Вакансия закрыта");

        when(postRepository.findById(POST_ID))
                .thenReturn(Optional.of(post));

        TelegramChannelPostArchiveResult result =
                archiveService.restore(POST_ID);

        assertTrue(result.changed());
        assertFalse(result.archived());
        assertNull(result.archivedAt());
        assertNull(result.archiveReason());

        assertFalse(post.isArchived());
        assertNull(post.getArchivedAt());
        assertNull(post.getArchiveReason());

        verify(postRepository).save(post);
    }

    @Test
    void doesNotRestorePostTwice() {
        TelegramChannelPost post =
                createPost(false);

        when(postRepository.findById(POST_ID))
                .thenReturn(Optional.of(post));

        TelegramChannelPostArchiveResult result =
                archiveService.restore(POST_ID);

        assertFalse(result.changed());
        assertFalse(result.archived());

        verify(postRepository, never())
                .save(any());
    }

    @Test
    void rejectsBlankArchiveReason() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                archiveService.archive(
                                        POST_ID,
                                        "   "
                                )
                );

        assertEquals(
                "Необходимо указать причину архивирования",
                exception.getMessage()
        );

        verifyNoInteractions(postRepository);
    }

    @Test
    void throwsExceptionWhenPostDoesNotExist() {
        when(postRepository.findById(POST_ID))
                .thenReturn(Optional.empty());

        assertThrows(
                TelegramChannelPostNotFoundException.class,
                () ->
                        archiveService.restore(POST_ID)
        );

        verify(postRepository, never())
                .save(any());
    }

    private TelegramChannelPost createPost(
            boolean archived
    ) {
        TelegramChannelPost post =
                new TelegramChannelPost();

        post.setId(POST_ID);
        post.setTelegramChatId(-1001234567890L);
        post.setTelegramMessageId(15L);
        post.setFreshnessStatus(
                TelegramChannelPostFreshnessStatus.ACTIVE
        );
        post.setArchived(archived);

        return post;
    }
}