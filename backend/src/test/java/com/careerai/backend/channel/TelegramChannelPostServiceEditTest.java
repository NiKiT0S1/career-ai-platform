package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramChannelPostServiceEditTest {
    private final TelegramChannelPostRepository posts = mock(TelegramChannelPostRepository.class);
    private final TelegramChannelPostMetadataService metadata = mock(TelegramChannelPostMetadataService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TelegramChannelPostService service = new TelegramChannelPostService(posts, metadata,
            new TelegramReplyReferenceExtractor(), events);

    @Test
    void editInvalidatesDatesPreservesPublicationAndTriggersReindexing() {
        var post = existing();
        post.setReplyToTelegramMessageId(9L);
        OffsetDateTime publishedAt = post.getPostedAt();
        when(posts.findByTelegramChatIdAndTelegramMessageId(-1001L, 10L)).thenReturn(Optional.of(post));
        when(posts.save(post)).thenReturn(post);
        service.saveOrUpdateChannelPost(new ObjectMapper().readTree("""
                {"chat":{"id":-1001},"message_id":10,"text":"Перенесли на 15 ноября 2026", "edit_date":1784000000}
                """), "{}", true);
        assertEquals("Перенесли на 15 ноября 2026", post.getText());
        assertEquals(publishedAt, post.getPostedAt());
        assertEquals(9L, post.getReplyToTelegramMessageId());
        assertEquals(TelegramChannelPostFreshnessStatus.UNKNOWN, post.getFreshnessStatus());
        assertNull(post.getExpiresAt());
        assertNull(post.getFreshnessCheckedAt());
        verify(metadata).createOrResetMetadata(post, true);
        verify(events).publishEvent(new TelegramChannelPostSavedEvent(10L));
    }

    @Test
    void lateOldEditCannotReplaceNewerSource() {
        var post = existing();
        post.setEditedAt(OffsetDateTime.parse("2026-09-01T12:00:00Z"));
        when(posts.findByTelegramChatIdAndTelegramMessageId(-1001L, 10L)).thenReturn(Optional.of(post));
        service.saveOrUpdateChannelPost(new ObjectMapper().readTree("""
                {"chat":{"id":-1001},"message_id":10,"text":"Старый текст", "edit_date":1784000000}
                """), "{}", true);
        assertEquals("10 августа 2026 состоится мероприятие", post.getText());
        verify(posts, never()).save(any());
        verifyNoInteractions(metadata, events);
    }

    @Test
    void redeliveredOriginalCannotUndoAReceivedEdit() {
        var post = existing();
        post.setEditedAt(OffsetDateTime.parse("2026-09-01T12:00:00Z"));
        when(posts.findByTelegramChatIdAndTelegramMessageId(-1001L, 10L)).thenReturn(Optional.of(post));
        service.saveOrUpdateChannelPost(new ObjectMapper().readTree("""
                {"chat":{"id":-1001},"message_id":10,"text":"Старая публикация"}
                """), "{}", false);
        assertEquals("10 августа 2026 состоится мероприятие", post.getText());
        verify(posts, never()).save(any());
        verifyNoInteractions(metadata, events);
    }

    @Test
    void changedRedeliveredContentAlsoResetsMetadata() {
        var post = existing();
        when(posts.findByTelegramChatIdAndTelegramMessageId(-1001L, 10L)).thenReturn(Optional.of(post));
        when(posts.save(post)).thenReturn(post);
        service.saveOrUpdateChannelPost(new ObjectMapper().readTree("""
                {"chat":{"id":-1001},"message_id":10,"text":"15 ноября 2026 состоится мероприятие"}
                """), "{}", false);
        verify(metadata).createOrResetMetadata(post, true);
        assertNull(post.getExpiresAt());
    }

    private TelegramChannelPost existing() {
        var post = new TelegramChannelPost();
        post.setId(10L);
        post.setText("10 августа 2026 состоится мероприятие");
        post.setPostedAt(OffsetDateTime.parse("2026-07-10T10:00:00+05:00"));
        post.setExpiresAt(OffsetDateTime.parse("2026-08-11T00:00:00+05:00"));
        post.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
        return post;
    }
}
