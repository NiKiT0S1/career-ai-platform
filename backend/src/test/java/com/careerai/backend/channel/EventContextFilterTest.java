package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventContextFilterTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("Asia/Almaty"));
    private final MultilingualDateTextParser parser = new MultilingualDateTextParser(
            new MultilingualMonthDictionary(), new MultilingualDateBoundaryDetector());
    private final Map<Long, TelegramChannelPostMetadata> metadata = new HashMap<>();
    private final TelegramChannelPostMetadataRepository repository = mock(TelegramChannelPostMetadataRepository.class);
    private final EventContextFilter filter;

    EventContextFilterTest() {
        when(repository.findByPostId(anyLong())).thenAnswer(invocation -> Optional.ofNullable(metadata.get(invocation.getArgument(0))));
        filter = new EventContextFilter(new EventTemporalEvidenceService(repository, parser, clock), parser, clock);
    }

    @Test
    void removesPastEventTogetherWithTimelessReplyAndPartialCancellation() {
        var original = event(1, "10 августа 2026 состоится мероприятие", "10 августа 2026");
        var reply = post(2, "Начало в 12:00, конец в 15:00, Open Space");
        var cancellation = post(3, "Раздача Coca-Cola отменена");
        var result = filter.filter(result(List.of(original, reply, cancellation),
                List.of(relation(reply, original, TelegramChannelPostRelationType.UPDATE),
                        relation(cancellation, original, TelegramChannelPostRelationType.CANCELLATION))), current());
        assertTrue(result.isEmpty());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void futureRescheduleKeepsOriginalAndAllChangesAsOneComponent() {
        var original = event(1, "10 августа 2026 состоится мероприятие", "10 августа 2026");
        var update = event(2, "Мероприятие перенесено на 15 ноября 2026", "15 ноября 2026");
        var cancellation = post(3, "Раздача напитка отменена");
        var result = filter.filter(result(List.of(original, update, cancellation),
                List.of(relation(update, original, TelegramChannelPostRelationType.CORRECTION),
                        relation(cancellation, original, TelegramChannelPostRelationType.CANCELLATION))), current());
        assertEquals(3, result.allPosts().size());
        assertEquals(2, result.relations().size());
    }

    @Test
    void dateInsidePartialCancellationCannotReschedulePastEvent() {
        var original = event(1, "Мероприятие 10 августа 2026", "10 августа 2026");
        var cancellation = event(2, "Раздача напитков 15 ноября 2026 отменена", "15 ноября 2026");
        var result = filter.filter(result(List.of(original, cancellation),
                List.of(relation(cancellation, original, TelegramChannelPostRelationType.CANCELLATION))), current());
        assertTrue(result.isEmpty());
    }

    @Test
    void datedPartialCancellationDoesNotExpireOtherwiseFutureEvent() {
        var original = event(1, "Мероприятие 15 ноября 2026", "15 ноября 2026");
        var cancellation = event(2, "Обещанная 10 августа 2026 раздача напитков отменена", "10 августа 2026");
        var result = filter.filter(result(List.of(original, cancellation),
                List.of(relation(cancellation, original, TelegramChannelPostRelationType.CANCELLATION))), current());
        assertEquals(2, result.allPosts().size());
    }

    @Test
    void unknownYearInLaterRescheduleBlocksUsingOlderPastDate() {
        var original = event(1, "10 августа 2026 состоится мероприятие", "10 августа 2026");
        var update = event(2, "Мероприятие перенесено на 15 ноября", "15 ноября");
        var result = filter.filter(result(List.of(original, update),
                List.of(relation(update, original, TelegramChannelPostRelationType.CORRECTION))), current());
        assertEquals(2, result.allPosts().size());
    }

    @Test
    void multiDayPeriodIsRetainedAsUncertainInsteadOfExpiringOnStartDate() {
        for (String range : List.of("18 сентября 2026 — 21 сентября 2026", "18–21 сентября 2026")) {
            var event = event(1, "Мероприятие проходит " + range, range);
            var result = filter.filter(result(List.of(event), List.of()), current());
            assertEquals(1, result.allPosts().size(), range);
            assertEquals(DateParseStatus.UNKNOWN,
                    new EventTemporalEvidenceService(repository, parser, clock).inspect(event).eventDate().status());
        }
    }

    @Test
    void dateOfPublicationDoesNotHideFutureEventAndTodayIsIncluded() {
        var today = event(1, "Мероприятие 19 сентября 2026", "19 сентября 2026");
        var future = event(2, "Мероприятие 15 ноября 2026", "15 ноября 2026");
        assertEquals(2, filter.filter(result(List.of(today, future), List.of()), current()).allPosts().size());
    }

    @Test
    void onlyPastComponentRemovedWhileIndependentFutureEventRemains() {
        var past = event(1, "Мероприятие 10 августа 2026", "10 августа 2026");
        var oldReply = post(2, "Начало в 12:00");
        var future = event(3, "Мероприятие 15 ноября 2026", "15 ноября 2026");
        var result = filter.filter(result(List.of(past, oldReply, future),
                List.of(relation(oldReply, past, TelegramChannelPostRelationType.UPDATE))), current());
        assertEquals(List.of(3L), result.allPosts().stream().map(TelegramChannelPost::getId).toList());
    }

    @Test
    void historicalPublicationQueryPreservesPastEvents() {
        var event = event(1, "Мероприятие 10 августа 2026", "10 августа 2026");
        var historical = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, "events", List.of(ChannelContentScope.EVENTS),
                ChannelResultMode.RELEVANT, true, false, false, null,
                ChannelTimeScope.ANY_TIME, ChannelFreshnessScope.ALL, null, null);
        assertEquals(1, filter.filter(result(List.of(event), List.of()), historical).allPosts().size());
    }

    @Test
    void occurrenceWindowUsesEventDateNotPublicationAndRetainsUncertainDates() {
        var august = event(1, "Мероприятие 10 августа 2026", "10 августа 2026");
        var november = event(2, "Мероприятие 15 ноября 2026", "15 ноября 2026");
        var uncertain = event(3, "Мероприятие 15 ноября", "15 ноября");
        var window = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, "events", List.of(ChannelContentScope.EVENTS),
                ChannelResultMode.RELEVANT, true, false, false, null,
                ChannelTimeScope.ANY_TIME, ChannelFreshnessScope.ALL, null, null,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        var result = filter.filter(result(List.of(august, november, uncertain), List.of()), window);
        assertEquals(List.of(1L, 3L), result.allPosts().stream().map(TelegramChannelPost::getId).toList());
    }

    @Test
    void pastEventStillAvailableWhenSameComponentAnswersRequestedDeadlineQuestion() {
        var event = event(1, "Мероприятие 10 августа 2026", "10 августа 2026");
        var result = new ChannelPostSearchResult(List.of(new ChannelPostSearchGroup(ChannelContentScope.EVENTS, List.of(event)),
                new ChannelPostSearchGroup(ChannelContentScope.DEADLINES, List.of(event))));
        assertEquals(1, filter.filter(result, current()).allPosts().size());
    }

    private ChannelQueryAnalysis current() {
        return new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, "events", List.of(ChannelContentScope.EVENTS),
                ChannelResultMode.RELEVANT, true, false, false);
    }
    private TelegramChannelPost event(long id, String text, String dateQuote) {
        var post = post(id, text);
        var data = new TelegramChannelPostMetadata();
        data.setPost(post);
        data.setPostType(TelegramChannelPostType.EVENT);
        data.setExtractionStatus(TelegramChannelPostExtractionStatus.SUCCESS);
        data.setEventDateText(dateQuote);
        metadata.put(id, data);
        return post;
    }
    private TelegramChannelPost post(long id, String text) {
        var post = new TelegramChannelPost();
        post.setId(id);
        post.setText(text);
        post.setPostedAt(OffsetDateTime.parse("2026-07-10T10:00:00+05:00").plusMinutes(id));
        return post;
    }
    private TelegramChannelPostRelation relation(TelegramChannelPost source, TelegramChannelPost target, TelegramChannelPostRelationType type) {
        var relation = new TelegramChannelPostRelation();
        relation.setSourcePost(source);
        relation.setTargetPost(target);
        relation.setRelationType(type);
        return relation;
    }
    private ChannelPostSearchResult result(List<TelegramChannelPost> posts, List<TelegramChannelPostRelation> relations) {
        return new ChannelPostSearchResult(List.of(new ChannelPostSearchGroup(ChannelContentScope.EVENTS, posts)), relations);
    }
}
