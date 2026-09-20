package com.careerai.backend.semantic;

import com.careerai.backend.answer.AnswerCacheProperties;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticQueryEmbeddingServiceTest {
    private final SemanticSearchProperties properties = mock(SemanticSearchProperties.class);
    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);
    private final SemanticQueryEmbeddingService service = new SemanticQueryEmbeddingService(properties, provider,
            new AnswerCacheProperties(10, 60, 10, 60), Clock.systemUTC());

    @Test
    void reusesSuccessfulVectorsWithoutLeakingMutableArrays() {
        enable();
        when(provider.embedQuery("jobs")).thenReturn(EmbeddingResult.success(new double[]{1, 2}, "model", 3));
        service.createQueryEmbedding("jobs").orElseThrow().values()[0] = 99;
        assertEquals(1, service.createQueryEmbedding("jobs").orElseThrow().values()[0]);
        verify(provider, times(1)).embedQuery("jobs");
        when(properties.getEmbeddingModel()).thenReturn("new-model");
        when(provider.embedQuery("jobs")).thenReturn(EmbeddingResult.success(new double[]{3, 4}, "new-model", 3));
        assertEquals("new-model", service.createQueryEmbedding("jobs").orElseThrow().model());
        verify(provider, times(2)).embedQuery("jobs");
    }

    @Test
    void failuresAndInvalidDimensionsOrNumbersAreNeverCached() {
        enable();
        when(provider.embedQuery("jobs"))
                .thenReturn(EmbeddingResult.failure("model", 0, "outage"))
                .thenReturn(EmbeddingResult.success(new double[]{1}, "model", 0))
                .thenReturn(EmbeddingResult.success(new double[]{1, Double.NaN}, "model", 0))
                .thenReturn(EmbeddingResult.success(new double[]{1, 2}, "model", 0));
        assertTrue(service.createQueryEmbedding("jobs").isEmpty());
        assertTrue(service.createQueryEmbedding("jobs").isEmpty());
        assertTrue(service.createQueryEmbedding("jobs").isEmpty());
        assertTrue(service.createQueryEmbedding("jobs").isPresent());
        verify(provider, times(4)).embedQuery("jobs");
    }

    @Test
    void disabledSemanticSearchNeverCallsProvider() {
        assertTrue(service.createQueryEmbedding("jobs").isEmpty());
        verifyNoInteractions(provider);
    }

    @Test
    void wrongModelAndZeroVectorAreRejectedAndCannotPoisonCache() {
        enable();
        when(provider.embedQuery("jobs"))
                .thenReturn(EmbeddingResult.success(new double[]{1, 2}, "wrong-model", 0))
                .thenReturn(EmbeddingResult.success(new double[]{0, -0.0}, "model", 0))
                .thenReturn(EmbeddingResult.success(new double[]{1, 2}, "model", 0));
        assertTrue(service.createQueryEmbedding("jobs").isEmpty());
        assertTrue(service.createQueryEmbedding("jobs").isEmpty());
        assertTrue(service.createQueryEmbedding("jobs").isPresent());
        assertTrue(service.createQueryEmbedding("jobs").isPresent());
        verify(provider, times(3)).embedQuery("jobs");
    }

    private void enable() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getEmbeddingModel()).thenReturn("model");
        when(properties.getOutputDimensions()).thenReturn(2);
    }
}
