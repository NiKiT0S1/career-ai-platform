package com.careerai.backend.channel;

import com.careerai.backend.semantic.SemanticContentHashService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StandaloneRelationServiceTest {
    private final StandaloneRelationCandidateRepository candidates = mock(StandaloneRelationCandidateRepository.class);
    private final StandaloneRelationAuditRepository audits = mock(StandaloneRelationAuditRepository.class);
    private final TelegramChannelPostRelationRepository relations = mock(TelegramChannelPostRelationRepository.class);
    private final TelegramChannelPostRelationRootResolver roots = mock(TelegramChannelPostRelationRootResolver.class);
    private final StandaloneRelationSelector selector = mock(StandaloneRelationSelector.class);
    private final StandaloneRelationPolicy policy = new StandaloneRelationPolicy(new SemanticContentHashService());
    private final StandaloneRelationClassifier classifier = mock(StandaloneRelationClassifier.class);
    private final EntityManager em = mock(EntityManager.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private StandaloneRelationService service;
    private StandaloneRelationCandidate candidate;

    @BeforeEach void setup() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new StandaloneRelationService(candidates, audits, relations, roots, selector, policy, classifier, em,
                Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC), transactions);
        candidate = new StandaloneRelationCandidate(); candidate.setId(1L);
        candidate.setSourcePost(StandaloneRelationPolicyTest.post(2)); candidate.setTargetPost(StandaloneRelationPolicyTest.post(1));
        candidate.setInputHash(policy.inputHash(candidate.getSourcePost(), candidate.getTargetPost()));
        candidate.setAutoApprovalEligible(true);
        when(em.find(StandaloneRelationCandidate.class, 1L)).thenReturn(candidate);
        when(roots.resolveRoot(candidate.getTargetPost())).thenReturn(new TelegramChannelPostRootResolution(candidate.getTargetPost(),
                TelegramChannelPostRootResolutionStatus.RESOLVED, "root"));
        when(relations.findAllBySourcePostIdWithTarget(2L)).thenReturn(List.of());
        when(relations.save(any())).thenAnswer(invocation -> {
            TelegramChannelPostRelation relation = invocation.getArgument(0); relation.setId(55L); return relation;
        });
    }

    @Test void createsOnlyHighConfidenceUnambiguousAutomaticRelationWithAudit() {
        when(classifier.classify(any())).thenReturn(result(.99));
        assertTrue(service.process(1L));
        assertEquals(StandaloneRelationCandidateStatus.AUTO_APPROVED, candidate.getStatus());
        ArgumentCaptor<TelegramChannelPostRelation> capture = ArgumentCaptor.forClass(TelegramChannelPostRelation.class);
        verify(relations).save(capture.capture());
        assertEquals(TelegramChannelPostRelationOrigin.STANDALONE_INFERRED, capture.getValue().getRelationOrigin());
        assertEquals(candidate.getInputHash(), capture.getValue().getClassificationInputHash());
        verify(audits, atLeast(3)).save(any());
    }

    @Test void uncertainResultWaitsForReviewAndDoesNotChangeAnswerRelations() {
        when(classifier.classify(any())).thenReturn(result(.9));
        service.process(1L);
        assertEquals(StandaloneRelationCandidateStatus.REVIEW_REQUIRED, candidate.getStatus());
        verify(relations, never()).save(any());
    }

    @Test void sourceEditedWhileLlmRunsCannotApplyOldResult() {
        when(classifier.classify(any())).thenAnswer(invocation -> {
            candidate.getSourcePost().setText("Changed while provider was running");
            return result(.99);
        });
        service.process(1L);
        assertEquals(StandaloneRelationCandidateStatus.SUPERSEDED, candidate.getStatus());
        verify(relations, never()).save(any());
    }

    @Test void allExistingRelationOriginsHavePriorityOverAutomaticInference() {
        TelegramChannelPostRelation existing = new TelegramChannelPostRelation();
        for (TelegramChannelPostRelationOrigin origin : TelegramChannelPostRelationOrigin.values()) {
            candidate.setStatus(StandaloneRelationCandidateStatus.PENDING);
            existing.setRelationOrigin(origin);
            when(relations.findAllBySourcePostIdWithTarget(2L)).thenReturn(List.of(existing));
            assertFalse(service.process(1L));
            assertEquals(StandaloneRelationCandidateStatus.SUPERSEDED, candidate.getStatus());
        }
        verify(classifier, never()).classify(any()); verify(relations, never()).save(any());
    }

    @Test void cyclicOrAmbiguousTargetCannotBeApproved() {
        candidate.setStatus(StandaloneRelationCandidateStatus.REVIEW_REQUIRED);
        when(roots.resolveRoot(candidate.getTargetPost())).thenReturn(new TelegramChannelPostRootResolution(null,
                TelegramChannelPostRootResolutionStatus.CYCLE_DETECTED, "cycle"));
        assertThrows(IllegalStateException.class, () -> service.approve(1L, TelegramChannelPostRelationType.UPDATE, "Reviewed", "admin"));
        verify(relations, never()).save(any());
    }

    @Test void administratorApprovalCreatesProtectedOrigin() {
        candidate.setStatus(StandaloneRelationCandidateStatus.REVIEW_REQUIRED);
        service.approve(1L, TelegramChannelPostRelationType.CORRECTION, "The date was corrected", "manager");
        assertEquals(StandaloneRelationCandidateStatus.APPROVED, candidate.getStatus());
        verify(relations).save(argThat(relation -> relation.getRelationOrigin() == TelegramChannelPostRelationOrigin.ADMIN_CONFIRMED));
    }

    @Test void rejectingAutoDecisionRetractsOnlyOwnUnmodifiedAutomaticRelation() {
        candidate.setStatus(StandaloneRelationCandidateStatus.AUTO_APPROVED); candidate.setRelationId(55L);
        TelegramChannelPostRelation protectedRelation = new TelegramChannelPostRelation(); protectedRelation.setId(55L);
        protectedRelation.setRelationOrigin(TelegramChannelPostRelationOrigin.MANUAL);
        protectedRelation.setClassificationInputHash(candidate.getInputHash());
        when(relations.findById(55L)).thenReturn(Optional.of(protectedRelation));
        service.reject(1L, "Different event", "manager");
        verify(relations, never()).delete(any());
        assertEquals(StandaloneRelationCandidateStatus.REJECTED, candidate.getStatus());
    }

    @Test void exhaustedAttemptsCannotBeResetByRepeatedRetryClicks() {
        candidate.setStatus(StandaloneRelationCandidateStatus.FAILED); candidate.setAttemptCount(3);
        assertThrows(IllegalStateException.class, () -> service.retry(1L, "manager"));
        assertFalse(service.process(1L));
        verify(classifier, never()).classify(any());
    }

    @Test void manualRetryKeepsAttemptsAndRecordsActor() {
        candidate.setStatus(StandaloneRelationCandidateStatus.FAILED); candidate.setAttemptCount(1);
        service.retry(1L, "manager");
        assertEquals(1, candidate.getAttemptCount()); assertEquals(StandaloneRelationCandidateStatus.PENDING, candidate.getStatus());
        verify(audits).save(argThat(audit -> "manager".equals(audit.getActor()) && "PENDING".equals(audit.getAction())));
    }

    @Test void rediscoveryOfSameInputDoesNotRequeueRejectedOrClassifiedPair() {
        var source = candidate.getSourcePost(); var target = candidate.getTargetPost();
        when(candidates.findActiveConnected(2L)).thenReturn(List.of());
        when(em.find(TelegramChannelPost.class, 2L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(source);
        when(selector.select(source)).thenReturn(List.of(new StandaloneRelationSelector.Selection(target, .9, true, "same event")));
        when(candidates.existsBySourcePostIdAndTargetPostIdAndInputHash(2L, 1L, candidate.getInputHash())).thenReturn(true);
        assertTrue(service.discover(2L).isEmpty());
        verify(candidates, never()).save(any());
    }

    @Test void editingTargetRetractsOwnedAutomaticRelationAndSupersedesOldInput() {
        candidate.setStatus(StandaloneRelationCandidateStatus.AUTO_APPROVED); candidate.setRelationId(55L);
        candidate.getTargetPost().setText("Changed target announcement");
        TelegramChannelPostRelation inferred = new TelegramChannelPostRelation(); inferred.setId(55L);
        inferred.setRelationOrigin(TelegramChannelPostRelationOrigin.STANDALONE_INFERRED);
        inferred.setClassificationInputHash(candidate.getInputHash());
        when(candidates.findActiveConnected(1L)).thenReturn(List.of(candidate));
        when(relations.findById(55L)).thenReturn(Optional.of(inferred));
        service.discover(1L);
        verify(relations).delete(inferred);
        assertEquals(StandaloneRelationCandidateStatus.SUPERSEDED, candidate.getStatus());
        verify(audits).save(argThat(audit -> "RELATION_RETRACTED".equals(audit.getAction())));
    }

    private StandaloneRelationClassifier.Result result(double confidence) {
        return new StandaloneRelationClassifier.Result(true, true, TelegramChannelPostRelationType.CANCELLATION,
                "Cancelled only the in-person session", confidence, true, "test", "test", "{}", null);
    }
}
