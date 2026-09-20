package com.careerai.backend.answer;

import com.careerai.backend.channel.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StructuredChannelAnswerBuilderTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneId.of("Asia/Almaty"));
    private final TelegramChannelPostRepository posts = mock(TelegramChannelPostRepository.class);
    private final StandaloneRelationCandidateRepository candidates = mock(StandaloneRelationCandidateRepository.class);
    private final StructuredChannelAnswerBuilder builder = new StructuredChannelAnswerBuilder(
            new TelegramChannelPostSearchEligibility(clock), posts, candidates, new AnswerExecutionPlanner(), clock);

    @Test
    void listContainsEscapedPreviewsAndSourceLinksWithLocalizedLabels() {
        var post = post(1L, "Java <Intern> & backend", "2026-09-18T12:00:00Z");
        when(posts.findLatestSearchableTextPosts(any())).thenReturn(List.of(post));
        String answer = builder.build("Show all vacancies", result(post), 30).orElseThrow();
        assertTrue(answer.contains("<b>Vacancies</b>"));
        assertTrue(answer.contains("&lt;Intern&gt; &amp;"));
        assertTrue(answer.contains("https://t.me/career_channel/1"));
        assertTrue(answer.contains("expiry unconfirmed"));
    }

    @Test
    void rejectsArchivedExpiredAndInvalidPostsEvenBeforeScheduledRefresh() {
        var post = post(1L, "Java", "2026-09-18T12:00:00Z");
        post.setArchived(true);
        assertTrue(builder.build("все вакансии", result(post), 30).isEmpty());
        post.setArchived(false);
        post.setFreshnessStatus(TelegramChannelPostFreshnessStatus.ACTIVE);
        post.setExpiresAt(OffsetDateTime.now(clock));
        assertTrue(builder.build("все вакансии", result(post), 30).isEmpty());
        post.setExpiresAt(null);
        post.setFreshnessStatus(TelegramChannelPostFreshnessStatus.INVALID);
        assertTrue(builder.build("все вакансии", result(post), 30).isEmpty());
    }

    @Test
    void unresolvedCandidateOrNewerUnlinkedCorrectionForcesRag() {
        var original = post(1L, "Java intern", "2026-09-18T12:00:00Z");
        when(candidates.hasUnresolvedForPostIds(any())).thenReturn(true);
        assertTrue(builder.build("all vacancies", result(original), 30).isEmpty());
        when(candidates.hasUnresolvedForPostIds(any())).thenReturn(false);
        var correction = post(2L, "Java intern opening cancelled", "2026-09-19T09:00:00Z");
        when(posts.findLatestSearchableTextPosts(any())).thenReturn(List.of(correction, original));
        assertTrue(builder.build("all vacancies", result(original), 30).isEmpty());
    }

    @Test
    void incompleteChainNeverRendersAPlainSourceList() {
        var post = post(1L, "Java Intern", "2026-09-18T12:00:00Z");
        var incomplete = new ChannelPostSearchResult(result(post).groups(), List.of(), false);
        assertTrue(builder.build("all vacancies", incomplete, 30).isEmpty());
        verifyNoInteractions(posts, candidates);
    }

    private ChannelPostSearchResult result(TelegramChannelPost post) {
        return new ChannelPostSearchResult(List.of(new ChannelPostSearchGroup(ChannelContentScope.VACANCIES, List.of(post))));
    }

    private TelegramChannelPost post(long id, String text, String postedAt) {
        var post = new TelegramChannelPost();
        post.setId(id);
        post.setTelegramMessageId(id);
        post.setChannelUsername("career_channel");
        post.setText(text);
        post.setPostedAt(OffsetDateTime.parse(postedAt));
        return post;
    }
}
