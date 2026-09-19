package com.careerai.backend.semantic;

import com.careerai.backend.channel.*;
import com.careerai.backend.faq.FaqEntry;
import com.careerai.backend.faq.FaqEntryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticSearchFreshnessTest {
    @Mock SemanticEmbeddingRepository embeddings;
    @Mock TelegramChannelPostRepository posts;
    @Mock TelegramChannelPostMetadataRepository metadata;
    @Mock FaqEntryService faqs;
    @Mock EmbeddingProvider provider;
    private SemanticSearchProperties properties;
    private final SemanticDocumentContent content = new SemanticDocumentContent(new SemanticContentHashService());
    private ChannelPostSemanticSearchService channelSearch;
    private FaqSemanticSearchService faqSearch;
    private final EmbeddingResult query = EmbeddingResult.success(new double[]{1, 0}, "model", 1);

    @BeforeEach
    void setUp() {
        properties = new SemanticSearchProperties();
        ReflectionTestUtils.setField(properties, "enabled", true);
        ReflectionTestUtils.setField(properties, "embeddingModel", "model");
        ReflectionTestUtils.setField(properties, "outputDimensions", 2);
        ReflectionTestUtils.setField(properties, "channelMaxResults", 5);
        ReflectionTestUtils.setField(properties, "faqMaxResults", 5);
        ReflectionTestUtils.setField(properties, "channelMinSimilarity", 0.5);
        ReflectionTestUtils.setField(properties, "faqMinSimilarity", 0.5);
        channelSearch = new ChannelPostSemanticSearchService(properties, embeddings, posts, metadata,
                new TelegramChannelPostSearchEligibility(Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneOffset.UTC)), content);
        faqSearch = new FaqSemanticSearchService(properties, provider, embeddings, faqs, content);
    }

    @Test
    void editedChannelPostCannotMatchUsingItsOldVector() {
        TelegramChannelPost post = post();
        String originalHash = content.channelHash(post);
        post.setText("Отменено: вакансий больше нет");
        prepareChannel(post, originalHash);
        assertTrue(channelSearch.findRelevantPosts(query, ChannelContentScope.VACANCIES).orElseThrow().isEmpty());
    }

    @Test
    void unchangedActiveChannelPostCanMatch() {
        TelegramChannelPost post = post();
        prepareChannel(post, content.channelHash(post));
        assertEquals(1, channelSearch.findRelevantPosts(query, ChannelContentScope.VACANCIES).orElseThrow().size());
    }

    @Test
    void archivedOrExpiredPostCannotMatchEvenIfVectorIsCurrent() {
        TelegramChannelPost post = post();
        post.setArchived(true);
        prepareChannel(post, content.channelHash(post));
        assertTrue(channelSearch.findRelevantPosts(query, ChannelContentScope.VACANCIES).orElseThrow().isEmpty());
        post.setArchived(false);
        post.setExpiresAt(OffsetDateTime.parse("2026-09-19T10:00:00Z"));
        assertTrue(channelSearch.findRelevantPosts(query, ChannelContentScope.VACANCIES).orElseThrow().isEmpty());
    }

    @Test
    void editedFaqCannotMatchUsingAnOldAnswerVector() {
        FaqEntry faq = new FaqEntry();
        faq.setId(1L);
        faq.setQuestion("Когда практика?");
        faq.setFullAnswer("С 1 сентября");
        String oldHash = content.faqHash(faq);
        faq.setFullAnswer("С 1 октября");
        when(faqs.findActiveEntries()).thenReturn(List.of(faq));
        when(embeddings.findBySourceType(SemanticSourceType.FAQ, "model", 2)).thenReturn(List.of(
                new SemanticEmbeddingVector(1, new double[]{1, 0}, oldHash)));
        assertTrue(faqSearch.findRelevantEntries(query).orElseThrow().isEmpty());
    }

    @Test
    void queryFromDifferentModelFallsBackWithoutReadingVectors() {
        EmbeddingResult wrongModel = EmbeddingResult.success(new double[]{1, 0}, "old-model", 1);
        assertTrue(channelSearch.findRelevantPosts(wrongModel, ChannelContentScope.VACANCIES).isEmpty());
        assertTrue(faqSearch.findRelevantEntries(wrongModel).isEmpty());
        verifyNoInteractions(embeddings, posts, metadata, faqs);
    }

    private TelegramChannelPost post() {
        TelegramChannelPost post = new TelegramChannelPost();
        post.setId(1L);
        post.setTelegramMessageId(100L);
        post.setText("Ищем Java-разработчика");
        post.setFreshnessStatus(TelegramChannelPostFreshnessStatus.ACTIVE);
        return post;
    }

    private void prepareChannel(TelegramChannelPost post, String hash) {
        TelegramChannelPostMetadata description = new TelegramChannelPostMetadata();
        description.setPost(post);
        description.setPostType(TelegramChannelPostType.VACANCY);
        when(embeddings.findBySourceType(SemanticSourceType.CHANNEL_POST, "model", 2)).thenReturn(List.of(
                new SemanticEmbeddingVector(1, new double[]{1, 0}, hash)));
        when(posts.findAllById(List.of(1L))).thenReturn(List.of(post));
        when(metadata.findSuccessfulByPostIds(List.of(1L))).thenReturn(List.of(description));
    }
}
