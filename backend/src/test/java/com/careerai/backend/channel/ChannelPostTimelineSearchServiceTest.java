package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelPostTimelineSearchServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneId.of("Asia/Almaty"));
    private final OffsetDateTime now = OffsetDateTime.now(clock);
    private final ChannelQueryWindowResolver.Window window = new ChannelQueryWindowResolver.Window(now.minusDays(7), now.plusDays(1));

    @Test
    void practiceDeadlineKnowledgeIncludesExpiredGenericNoticesButNotManualArchive() {
        var repository = mock(ChannelPostTimelineRepository.class);
        var relations = mock(TelegramChannelPostRelationExpansionService.class);
        var expired = post(7); expired.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
        var archived = post(8); archived.setArchived(true);
        when(repository.findDeadlineKnowledge(anyCollection(), eq(true), any(), any(), any()))
                .thenReturn(List.of(expired, archived));
        when(relations.expand(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new ChannelPostTimelineSearchService(repository, new ChannelQueryWindowResolver(clock), relations, clock);
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.PRACTICE, "practice_deadline",
                List.of(ChannelContentScope.PRACTICE), ChannelResultMode.RELEVANT, true, true, true);
        assertEquals(List.of(expired), service.searchDeadlineKnowledge(analysis, 8).allPosts());
        verify(repository).findDeadlineKnowledge(eq(List.of(TelegramChannelPostType.PRACTICE, TelegramChannelPostType.DEADLINE)),
                eq(true), any(), any(), any());
    }

    @Test
    void allNeverOverridesManualArchiveOrInvalidDates() {
        var post = post(1);
        assertTrue(ChannelPostTimelineSearchService.matches(post, ChannelFreshnessScope.ALL, window, now));
        post.setArchived(true);
        assertFalse(ChannelPostTimelineSearchService.matches(post, ChannelFreshnessScope.ALL, window, now));
        post.setArchived(false);
        post.setFreshnessStatus(TelegramChannelPostFreshnessStatus.INVALID);
        assertFalse(ChannelPostTimelineSearchService.matches(post, ChannelFreshnessScope.ALL, window, now));
    }

    @Test
    void expiredBoundaryIsEffectiveEvenBeforeFreshnessScheduler() {
        var post = post(1);
        post.setFreshnessStatus(TelegramChannelPostFreshnessStatus.ACTIVE);
        post.setExpiresAt(now);
        assertFalse(ChannelPostTimelineSearchService.matches(post, ChannelFreshnessScope.CURRENT, window, now));
        assertTrue(ChannelPostTimelineSearchService.matches(post, ChannelFreshnessScope.EXPIRED, window, now));
        assertTrue(ChannelPostTimelineSearchService.matches(post, ChannelFreshnessScope.ALL, window, now));
        post.setExpiresAt(now.plusNanos(1));
        assertTrue(ChannelPostTimelineSearchService.matches(post, ChannelFreshnessScope.CURRENT, window, now));
        assertFalse(ChannelPostTimelineSearchService.matches(post, ChannelFreshnessScope.EXPIRED, window, now));
    }

    @Test
    void filteringUsesPublicationDateAndDoesNotCountAnEditAsNewPublication() {
        var post = post(1);
        post.setPostedAt(now.minusDays(8));
        post.setEditedAt(now);
        assertFalse(ChannelPostTimelineSearchService.matches(post, ChannelFreshnessScope.ALL, window, now));
    }

    @Test
    @SuppressWarnings("unchecked")
    void timelinePreservesEveryRequestedCategoryAndExpandsOnlyAllowedHistoricalContext() {
        var repository = mock(ChannelPostTimelineRepository.class);
        var relations = mock(TelegramChannelPostRelationExpansionService.class);
        when(repository.findInWindow(anyCollection(), anyBoolean(), anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    int limit = ((Pageable) invocation.getArgument(6)).getPageSize();
                    return java.util.stream.IntStream.range(0, limit).mapToObj(this::post).toList();
                });
        when(relations.expand(any(), any())).thenAnswer(invocation -> {
            Predicate<TelegramChannelPost> allowed = invocation.getArgument(1);
            var historical = post(100);
            historical.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
            assertTrue(allowed.test(historical));
            historical.setArchived(true);
            assertFalse(allowed.test(historical));
            return invocation.getArgument(0);
        });
        var service = new ChannelPostTimelineSearchService(repository, new ChannelQueryWindowResolver(clock), relations, clock);
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null,
                List.of(ChannelContentScope.VACANCIES, ChannelContentScope.EVENTS), ChannelResultMode.ALL_MATCHING,
                true, false, false, null, ChannelTimeScope.TODAY, ChannelFreshnessScope.ALL, null, null);
        var result = service.search(analysis, 30);
        assertEquals(2, result.groups().size());
        assertEquals(15, result.groups().get(0).posts().size());
        assertEquals(15, result.groups().get(1).posts().size());
        verify(repository, times(2)).findInWindow(anyCollection(), anyBoolean(), eq("ALL"), any(), any(), eq(now), any());
    }

    @Test
    void invalidCustomRangeDoesNotQueryOrExpandAnything() {
        var repository = mock(ChannelPostTimelineRepository.class);
        var relations = mock(TelegramChannelPostRelationExpansionService.class);
        var service = new ChannelPostTimelineSearchService(repository, new ChannelQueryWindowResolver(clock), relations, clock);
        assertTrue(service.search(ChannelQueryWindowResolverTest.query(ChannelTimeScope.CUSTOM_RANGE, null, null), 30).isEmpty());
        verifyNoInteractions(repository, relations);
    }

    private TelegramChannelPost post(int id) {
        var post = new TelegramChannelPost();
        post.setId((long) id);
        post.setText("Вакансия");
        post.setPostedAt(now.minusHours(1));
        return post;
    }
}
