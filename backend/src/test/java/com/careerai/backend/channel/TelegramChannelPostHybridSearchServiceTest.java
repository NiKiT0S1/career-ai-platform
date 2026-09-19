package com.careerai.backend.channel;

import com.careerai.backend.semantic.ChannelPostSemanticSearchService;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramChannelPostHybridSearchServiceTest {
    @Test
    void allMatchingFirstCategoryCannotConsumeSecondCategoryBudget() {
        var structured = mock(TelegramChannelPostStructuredSearchService.class);
        var semantic = mock(ChannelPostSemanticSearchService.class);
        var relations = mock(TelegramChannelPostRelationExpansionService.class);
        when(relations.expand(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(structured.findRelevantPostsForScope(any(), any(), anyInt())).thenAnswer(invocation -> {
            ChannelContentScope scope = invocation.getArgument(1);
            int limit = invocation.getArgument(2);
            return java.util.stream.IntStream.range(0, limit).mapToObj(i -> {
                var post = new TelegramChannelPost();
                post.setId((long) (scope.ordinal() * 100 + i));
                return post;
            }).toList();
        });
        var service = new TelegramChannelPostHybridSearchService(structured, semantic, relations,
                new TelegramChannelPostSearchEligibility(Clock.systemUTC()));
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.VACANCY, null,
                List.of(ChannelContentScope.VACANCIES, ChannelContentScope.EVENTS), ChannelResultMode.ALL_MATCHING,
                true, false, false);
        var result = service.findRelevantPosts(analysis, Optional.empty(), 30);
        assertEquals(2, result.groups().size());
        assertEquals(15, result.groups().getFirst().posts().size());
        assertEquals(15, result.groups().getLast().posts().size());
        verify(structured).findRelevantPostsForScope(analysis, ChannelContentScope.VACANCIES, 15);
        verify(structured).findRelevantPostsForScope(analysis, ChannelContentScope.EVENTS, 15);
    }
}
