package com.careerai.backend.channel;

import com.careerai.backend.ai.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramChannelPostMetadataExtractorServiceTest {
    private final TelegramChannelPostMetadataRepository repository = mock(TelegramChannelPostMetadataRepository.class);
    private final LlmProvider provider = mock(LlmProvider.class);
    private final TelegramChannelPostFreshnessService freshness = mock(TelegramChannelPostFreshnessService.class);
    private final TelegramChannelPostMetadataExtractorService service = new TelegramChannelPostMetadataExtractorService(
            repository, provider, new ObjectMapper(), new TelegramChannelPostMetadataRequestFactory(), freshness);

    @Test
    void storesEventDateAndRegistrationDeadlineAsDifferentSourceQuotes() {
        var metadata = source("Регистрация до 5 августа 2026. Мероприятие 10 августа 2026.");
        when(repository.findByIdWithPost(20L)).thenReturn(Optional.of(metadata));
        when(provider.execute(any())).thenReturn(success("""
                {"postType":"EVENT","eventDateText":"10 августа 2026","deadlineText":"до 5 августа 2026"}
                """));
        service.extractAndSave(20L);
        assertEquals("10 августа 2026", metadata.getEventDateText());
        assertEquals("до 5 августа 2026", metadata.getDeadlineText());
        assertEquals(TelegramChannelPostExtractionStatus.SUCCESS, metadata.getExtractionStatus());
        verify(freshness).recalculateOne(10L);
    }

    @Test
    void inventedYearAndPublicationMonthAreNotStoredAsExtractedDates() {
        var metadata = source("Мероприятие 10 августа, регистрация до 5 августа.");
        when(repository.findByIdWithPost(20L)).thenReturn(Optional.of(metadata));
        when(provider.execute(any())).thenReturn(success("""
                {"postType":"EVENT","eventDateText":"10 июля 2026","deadlineText":"до 5 августа 2026"}
                """));
        service.extractAndSave(20L);
        assertNull(metadata.getEventDateText());
        assertNull(metadata.getDeadlineText());
    }

    @Test
    void resultForOldTextCannotOverwritePendingEditedRevision() {
        var old = source("Мероприятие 10 августа 2026");
        var edited = source("Мероприятие перенесено на 15 ноября 2026");
        edited.setRevision(1L);
        when(repository.findByIdWithPost(20L)).thenReturn(Optional.of(old), Optional.of(edited));
        when(provider.execute(any())).thenReturn(success("""
                {"postType":"EVENT","eventDateText":"10 августа 2026"}
                """));
        service.extractAndSave(20L);
        verify(repository, never()).save(any());
        verifyNoInteractions(freshness);
        assertEquals(TelegramChannelPostExtractionStatus.PENDING, edited.getExtractionStatus());
        assertNull(edited.getEventDateText());
    }

    @Test
    void providerFailureForOldTextCannotMarkNewRevisionFailed() {
        var old = source("Мероприятие 10 августа 2026");
        var edited = source("Мероприятие перенесено на 15 ноября 2026");
        edited.setRevision(1L);
        when(repository.findByIdWithPost(20L)).thenReturn(Optional.of(old), Optional.of(edited));
        when(provider.execute(any())).thenReturn(LlmResponse.failure("unavailable", "stub", "stub", LlmErrorType.SERVICE_UNAVAILABLE, 1));
        service.extractAndSave(20L);
        verify(repository, never()).save(any());
        assertEquals(TelegramChannelPostExtractionStatus.PENDING, edited.getExtractionStatus());
    }

    @Test
    void optimisticConflictDuringSaveDoesNotPersistStaleFailure() {
        var metadata = source("Мероприятие 10 августа 2026");
        when(repository.findByIdWithPost(20L)).thenReturn(Optional.of(metadata));
        when(provider.execute(any())).thenReturn(success("""
                {"postType":"EVENT","eventDateText":"10 августа 2026"}
                """));
        when(repository.save(metadata)).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                TelegramChannelPostMetadata.class, 20L));
        service.extractAndSave(20L);
        verify(repository, times(1)).save(metadata);
        verifyNoInteractions(freshness);
    }

    private TelegramChannelPostMetadata source(String text) {
        var post = new TelegramChannelPost();
        post.setId(10L);
        post.setText(text);
        var metadata = new TelegramChannelPostMetadata();
        metadata.setId(20L);
        metadata.setPost(post);
        return metadata;
    }
    private LlmResponse success(String json) { return LlmResponse.success(json, "stub", "stub", 1); }
}
