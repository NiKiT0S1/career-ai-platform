package com.careerai.backend.channel;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Filters event occurrences, not publication dates, without separating an update from its source. */
@Service
public class EventContextFilter {
    private final EventTemporalEvidenceService evidenceService;
    private final MultilingualDateTextParser parser;
    private final Clock clock;

    public EventContextFilter(EventTemporalEvidenceService evidenceService, MultilingualDateTextParser parser, Clock clock) {
        this.evidenceService = evidenceService;
        this.parser = parser;
        this.clock = clock;
    }

    public ChannelPostSearchResult filter(ChannelPostSearchResult result, ChannelQueryAnalysis analysis) {
        if (result == null) return ChannelPostSearchResult.empty();
        if (analysis == null || result.isEmpty()
                || (!analysis.hasScope(ChannelContentScope.EVENTS) && !analysis.hasScope(ChannelContentScope.ALL_UPDATES))) {
            return result;
        }
        LocalDate from = analysis.eventDateFrom();
        LocalDate to = analysis.eventDateTo();
        boolean currentEvents = from == null && to == null
                && analysis.timeScope() == ChannelTimeScope.ANY_TIME
                && analysis.freshnessScope() == ChannelFreshnessScope.CURRENT;
        if (!currentEvents && from == null && to == null) return result;

        Map<Long, TelegramChannelPost> posts = new LinkedHashMap<>();
        Map<Long, Set<Long>> links = new HashMap<>();
        for (TelegramChannelPost post : result.allPosts()) {
            posts.put(post.getId(), post);
            links.put(post.getId(), new LinkedHashSet<>());
        }
        for (TelegramChannelPostRelation relation : result.relations()) {
            if (relation.getSourcePost() == null || relation.getTargetPost() == null) continue;
            Long source = relation.getSourcePost().getId(), target = relation.getTargetPost().getId();
            if (!links.containsKey(source) || !links.containsKey(target)) continue;
            links.get(source).add(target);
            links.get(target).add(source);
        }
        Set<Long> visited = new HashSet<>(), removed = new HashSet<>();
        for (Long start : posts.keySet()) {
            if (!visited.add(start)) continue;
            Set<Long> component = new LinkedHashSet<>();
            Deque<Long> remaining = new ArrayDeque<>();
            remaining.add(start);
            while (!remaining.isEmpty()) {
                Long id = remaining.removeFirst();
                component.add(id);
                for (Long adjacent : links.get(id)) if (visited.add(adjacent)) remaining.addLast(adjacent);
            }
            // The same source may also answer a requested historical deadline/practice question.
            // In that case keep its knowledge and let the dated prompt qualify its event status.
            boolean servesAnotherRequestedCategory = result.groups().stream()
                    .anyMatch(group -> group.scope() != ChannelContentScope.EVENTS && group.scope() != ChannelContentScope.ALL_UPDATES
                            && group.posts().stream().anyMatch(post -> component.contains(post.getId())));
            if (servesAnotherRequestedCategory) continue;
            LocalDate eventDate = latestConfidentDate(component.stream().map(posts::get).toList(), result.relations());
            if (eventDate == null) continue;
            if ((currentEvents && eventDate.isBefore(LocalDate.now(clock)))
                    || (from != null && eventDate.isBefore(from)) || (to != null && eventDate.isAfter(to))) {
                removed.addAll(component);
            }
        }
        if (removed.isEmpty()) return result;
        List<ChannelPostSearchGroup> groups = result.groups().stream()
                .map(group -> new ChannelPostSearchGroup(group.scope(), group.posts().stream()
                        .filter(post -> !removed.contains(post.getId())).toList())).toList();
        List<TelegramChannelPostRelation> relations = result.relations().stream()
                .filter(relation -> relation.getSourcePost() != null && relation.getTargetPost() != null
                        && !removed.contains(relation.getSourcePost().getId()) && !removed.contains(relation.getTargetPost().getId()))
                .toList();
        return new ChannelPostSearchResult(groups, relations, result.relationContextComplete());
    }

    private LocalDate latestConfidentDate(List<TelegramChannelPost> posts, List<TelegramChannelPostRelation> relations) {
        List<DatedEvidence> candidates = new ArrayList<>();
        for (TelegramChannelPost post : posts) {
            boolean cancellationOnly = relations.stream().anyMatch(relation -> relation.getSourcePost() != null
                    && Objects.equals(relation.getSourcePost().getId(), post.getId())
                    && relation.getRelationType() == TelegramChannelPostRelationType.CANCELLATION)
                    && relations.stream().noneMatch(relation -> relation.getSourcePost() != null
                    && Objects.equals(relation.getSourcePost().getId(), post.getId())
                    && relation.getRelationType() != TelegramChannelPostRelationType.CANCELLATION);
            // A date quoted in a cancellation identifies the affected promise; it does not reschedule the event.
            if (cancellationOnly) continue;
            var evidence = evidenceService.inspect(post);
            if (!evidence.eventRelated()) continue;
            boolean confirmed = post.hasCurrentDateConfirmation()
                    && post.getConfirmedDatePurpose() == ChannelPostDatePurpose.EVENT_DATE;
            String dateText = evidence.eventDateText() != null ? evidence.eventDateText() : post.getText();
            if (!confirmed && evidence.eventDateText() == null && evidence.deadlineText() != null) continue;
            if (!confirmed && parser.calendarMentions(dateText).isEmpty()) continue;
            // Keep uncertain ranges. The start of a multiday event cannot prove it has ended.
            boolean range = !confirmed && EventTemporalEvidenceService.containsUnresolvedRange(parser, dateText);
            LocalDate date = !range && evidence.eventDate().status() == DateParseStatus.PARSED
                    ? evidence.eventDate().date() : null;
            candidates.add(new DatedEvidence(effectiveDate(post), date));
        }
        candidates.sort(Comparator.comparing(DatedEvidence::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (candidates.isEmpty()) return null;
        DatedEvidence latest = candidates.getFirst();
        // A later unknown date blocks fallback to an older, previously confirmed date.
        if (latest.date() == null) return null;
        boolean tieConflict = candidates.stream().anyMatch(candidate -> Objects.equals(candidate.updatedAt(), latest.updatedAt())
                && !Objects.equals(candidate.date(), latest.date()));
        return tieConflict ? null : latest.date();
    }

    private OffsetDateTime effectiveDate(TelegramChannelPost post) {
        return post.getEditedAt() != null ? post.getEditedAt()
                : post.getPostedAt() != null ? post.getPostedAt() : post.getCreatedAt();
    }
    private record DatedEvidence(OffsetDateTime updatedAt, LocalDate date) {}
}
