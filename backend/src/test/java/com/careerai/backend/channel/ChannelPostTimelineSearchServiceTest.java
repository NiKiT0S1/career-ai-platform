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

    @Test
    void currentPublicationWindowIncludesExpiredDeadlineEvidenceOnlyInsideThatWindow() {
        var repository = mock(ChannelPostTimelineRepository.class);
        var relations = mock(TelegramChannelPostRelationExpansionService.class);
        var expiredYesterday = post(20);
        expiredYesterday.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
        expiredYesterday.setPostedAt(now.minusDays(1));
        var outsideWindow = post(21);
        outsideWindow.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
        outsideWindow.setPostedAt(now.minusDays(2));
        when(repository.findInWindow(anyCollection(), anyBoolean(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(repository.findDeadlineKnowledge(anyCollection(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of(expiredYesterday, outsideWindow));
        when(relations.expand(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new ChannelPostTimelineSearchService(repository, new ChannelQueryWindowResolver(clock), relations, clock);
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.PRACTICE, null,
                List.of(ChannelContentScope.PRACTICE), ChannelResultMode.RELEVANT, true, true, true, null,
                ChannelTimeScope.YESTERDAY, ChannelFreshnessScope.CURRENT, null, null);
        var result = service.search(analysis, 8, "Что вчера писали про документы на практику, я ещё успеваю?");
        assertEquals(List.of(expiredYesterday), result.allPosts());
        var window = new ChannelQueryWindowResolver(clock).resolve(analysis).orElseThrow();
        verify(repository).findDeadlineKnowledge(eq(List.of(TelegramChannelPostType.PRACTICE, TelegramChannelPostType.DEADLINE)),
                eq(true), eq(window.fromInclusive()), eq(window.toExclusive()), any());
    }

    @Test
    void requestedJavaDeadlineIsFoundBehindEightNewerUnrelatedPostsWithoutUsingInventedTopic() {
        var repository = mock(ChannelPostTimelineRepository.class);
        var relations = mock(TelegramChannelPostRelationExpansionService.class);
        List<TelegramChannelPost> candidates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            var python = post(i + 20); python.setText("Python internship deadline 1 September 2026");
            python.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED); candidates.add(python);
        }
        var java = post(100); java.setText("Java Developer: документы до 7 сентября 2026 года");
        java.setPostedAt(now.minusDays(30)); java.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
        candidates.add(java);
        when(repository.findDeadlineKnowledge(anyCollection(), anyBoolean(), any(), any(), any()))
                .thenAnswer(invocation -> candidates.stream().limit(((Pageable) invocation.getArgument(4)).getPageSize()).toList());
        when(relations.expand(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new ChannelPostTimelineSearchService(repository, new ChannelQueryWindowResolver(clock), relations, clock);
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.VACANCY, "invented_python_topic",
                List.of(ChannelContentScope.VACANCIES), ChannelResultMode.RELEVANT, true, false, true);
        assertEquals(List.of(java), service.searchDeadlineKnowledge(analysis, 8, "Дедлайн Java-вакансии уже прошёл?").allPosts());
        verify(repository).findDeadlineKnowledge(anyCollection(), eq(false), any(), any(),
                argThat(page -> page.getPageSize() == 200));
    }

    @Test
    void explicitExpiredDeadlineNeverReturnsCurrentPostsFromSupplement() {
        var repository = mock(ChannelPostTimelineRepository.class);
        var relations = mock(TelegramChannelPostRelationExpansionService.class);
        var expired = post(20); expired.setText("Java vacancy deadline");
        expired.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
        var current = post(21); current.setText("Java vacancy deadline");
        current.setFreshnessStatus(TelegramChannelPostFreshnessStatus.ACTIVE);
        when(repository.findInWindow(anyCollection(), anyBoolean(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(current, expired));
        when(repository.findDeadlineKnowledge(anyCollection(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of(current, expired));
        when(relations.expand(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new ChannelPostTimelineSearchService(repository, new ChannelQueryWindowResolver(clock), relations, clock);
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.VACANCY, null,
                List.of(ChannelContentScope.VACANCIES), ChannelResultMode.RELEVANT, true, false, true, null,
                ChannelTimeScope.ANY_TIME, ChannelFreshnessScope.EXPIRED, null, null);
        assertEquals(List.of(expired), service.search(analysis, 8, "Покажи истёкшие дедлайны Java-вакансий").allPosts());
    }

    @Test
    void javaVacancyConstraintDoesNotHideGeneralPracticeDocumentDeadlineInMixedQuestion() {
        var repository = mock(ChannelPostTimelineRepository.class);
        var relations = mock(TelegramChannelPostRelationExpansionService.class);
        var java = post(20); java.setText("Java vacancy deadline 7 September");
        var practice = post(21); practice.setText("Документы на практику до 7 сентября");
        when(repository.findDeadlineKnowledge(anyCollection(), eq(false), any(), any(), any())).thenReturn(List.of(java));
        when(repository.findDeadlineKnowledge(anyCollection(), eq(true), any(), any(), any())).thenReturn(List.of(practice));
        when(relations.expand(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new ChannelPostTimelineSearchService(repository, new ChannelQueryWindowResolver(clock), relations, clock);
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.VACANCY, "java",
                List.of(ChannelContentScope.VACANCIES, ChannelContentScope.PRACTICE), ChannelResultMode.RELEVANT, true, true, true);
        assertEquals(List.of(java, practice), service.searchDeadlineKnowledge(analysis, 8,
                "Какие Java-вакансии и до какого числа документы на практику?").allPosts());
    }

    private TelegramChannelPost post(int id) {
        var post = new TelegramChannelPost();
        post.setId((long) id);
        post.setText("Вакансия");
        post.setPostedAt(now.minusHours(1));
        return post;
    }
}
