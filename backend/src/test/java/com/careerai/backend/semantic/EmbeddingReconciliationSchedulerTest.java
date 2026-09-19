package com.careerai.backend.semantic;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingReconciliationSchedulerTest {
    @Test
    void startupDoesNoApiWorkAndOnlyOneBatchCanBeQueued() {
        SemanticEmbeddingLifecycleService lifecycle = mock(SemanticEmbeddingLifecycleService.class);
        List<Runnable> tasks = new ArrayList<>();
        EmbeddingReconciliationScheduler scheduler = new EmbeddingReconciliationScheduler(lifecycle, tasks::add);
        scheduler.onStartup();
        verify(lifecycle).resetScan();
        verify(lifecycle, never()).reconcileBatch();
        scheduler.reconcile();
        scheduler.reconcile();
        assertEquals(1, tasks.size());
        tasks.getFirst().run();
        verify(lifecycle).reconcileBatch();
        scheduler.reconcile();
        assertEquals(2, tasks.size());
    }

    @Test
    void rejectedExecutorCanBeRetriedOnNextTick() {
        SemanticEmbeddingLifecycleService lifecycle = mock(SemanticEmbeddingLifecycleService.class);
        java.util.concurrent.Executor executor = mock(java.util.concurrent.Executor.class);
        doThrow(new RejectedExecutionException("busy")).doAnswer(invocation -> null).when(executor).execute(any());
        EmbeddingReconciliationScheduler scheduler = new EmbeddingReconciliationScheduler(lifecycle, executor);
        assertDoesNotThrow(scheduler::reconcile);
        scheduler.reconcile();
        verify(executor, times(2)).execute(any());
    }
}
