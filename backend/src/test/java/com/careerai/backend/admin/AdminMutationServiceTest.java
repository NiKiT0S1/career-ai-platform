package com.careerai.backend.admin;

import com.careerai.backend.channel.*;
import com.careerai.backend.faq.FaqEntry;
import com.careerai.backend.semantic.FaqEntryChangedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminMutationServiceTest {
    @Mock EntityManager em;
    @Mock TelegramChannelPostArchiveService archives;
    @Mock TelegramChannelPostFreshnessService freshness;
    @Mock TelegramChannelPostRelationService relations;
    @Mock AdminAuditService audit;
    @Mock ApplicationEventPublisher events;
    private AdminMutationService service;

    @BeforeEach
    void setUp() {
        service = new AdminMutationService(em, archives, freshness, relations, audit, events,
                Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void staleFaqRevisionCannotOverwriteAnswerOrPublishAnIndexEvent() {
        FaqEntry entry = faq();
        when(em.find(FaqEntry.class, 10L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(entry);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.saveFaq(424242, 10L, faqInput(3L, true)));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("Original answer", entry.getFullAnswer());
        verify(em, never()).flush();
        verifyNoInteractions(audit, events);
    }

    @Test
    void missingRevisionAlsoRejectsExistingFaqUpdate() {
        when(em.find(FaqEntry.class, 10L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(faq());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.saveFaq(424242, 10L, faqInput(null, true))).getStatusCode());
        verifyNoInteractions(audit, events);
    }

    @Test
    void validFaqEditAuditsAuthenticatedActorAndPublishesOnlyAfterFlush() {
        FaqEntry entry = faq();
        when(em.find(FaqEntry.class, 10L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(entry);
        assertEquals(10, service.saveFaq(424242, 10L, faqInput(4L, true)));
        assertEquals("Updated answer", entry.getFullAnswer());
        var order = inOrder(em, audit, events);
        order.verify(em).flush();
        order.verify(audit).record(424242, "update", "faq", 10L, "slug=practice");
        order.verify(events).publishEvent(new FaqEntryChangedEvent(10));
    }

    @Test
    void deactivatingFaqStillTriggersLifecycleCleanup() {
        FaqEntry entry = faq();
        when(em.find(FaqEntry.class, 10L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(entry);
        service.saveFaq(424242, 10L, faqInput(4L, false));
        assertFalse(entry.getActive());
        verify(events).publishEvent(new FaqEntryChangedEvent(10));
    }

    @Test
    void createsFaqWithGeneratedDatabaseIdBeforePublishing() {
        doAnswer(invocation -> { ((FaqEntry) invocation.getArgument(0)).setId(12L); return null; }).when(em).persist(any(FaqEntry.class));
        assertEquals(12, service.saveFaq(424242, null, faqInput(null, true)));
        verify(audit).record(424242, "create", "faq", 12L, "slug=practice");
        verify(events).publishEvent(new FaqEntryChangedEvent(12));
    }

    @Test
    void failedFlushDoesNotPublishEventOrAuditSuccess() {
        FaqEntry entry = faq();
        when(em.find(FaqEntry.class, 10L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(entry);
        doThrow(new IllegalStateException("conflict")).when(em).flush();
        assertThrows(IllegalStateException.class, () -> service.saveFaq(424242, 10L, faqInput(4L, true)));
        verifyNoInteractions(audit, events);
    }

    @Test
    void staleArchiveRequestCannotReachArchiveService() {
        TelegramChannelPost post = new TelegramChannelPost();
        post.setId(1L); post.setRevision(5);
        when(em.find(TelegramChannelPost.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(post);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.postAction(424242, 1, "archive", new AdminMutationService.PostAction(4L, "Closed"))).getStatusCode());
        verifyNoInteractions(archives, freshness, audit, events);
    }

    @Test
    void humanConfirmedRelationCannotBeSentBackToAutomaticClassifier() {
        TelegramChannelPostRelation relation = new TelegramChannelPostRelation();
        relation.setId(7L); relation.setEntityVersion(3L);
        relation.setRelationOrigin(TelegramChannelPostRelationOrigin.ADMIN_CONFIRMED);
        when(em.find(TelegramChannelPostRelation.class, 7L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(relation);
        assertThrows(IllegalArgumentException.class,
                () -> service.retryRelation(424242, 7, new AdminMutationService.PostAction(3L, "Retry")));
        verifyNoInteractions(relations, audit);
    }

    @Test
    void missingEntityReturnsNotFoundWithoutOtherSideEffects() {
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.postAction(424242, 9, "restore", new AdminMutationService.PostAction(0L, null))).getStatusCode());
        verifyNoInteractions(archives, freshness, relations, audit, events);
    }

    private FaqEntry faq() {
        FaqEntry entry = new FaqEntry();
        entry.setId(10L); entry.setRevision(4); entry.setFullAnswer("Original answer");
        return entry;
    }

    private AdminMutationService.FaqInput faqInput(Long revision, boolean active) {
        return new AdminMutationService.FaqInput(revision, "Practice", "practice", "Question", "Short answer",
                " Updated answer ", "practice", 10, active);
    }
}
