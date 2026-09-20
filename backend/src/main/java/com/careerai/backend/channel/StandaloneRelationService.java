package com.careerai.backend.channel;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;

/** Candidate lifecycle. Network calls happen after the claim transaction and before the result transaction. */
@Service
public class StandaloneRelationService {
    private final StandaloneRelationCandidateRepository candidates;
    private final StandaloneRelationAuditRepository audits;
    private final TelegramChannelPostRelationRepository relations;
    private final TelegramChannelPostRelationRootResolver roots;
    private final StandaloneRelationSelector selector;
    private final StandaloneRelationPolicy policy;
    private final StandaloneRelationClassifier classifier;
    private final EntityManager em;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public StandaloneRelationService(StandaloneRelationCandidateRepository candidates, StandaloneRelationAuditRepository audits,
                                    TelegramChannelPostRelationRepository relations, TelegramChannelPostRelationRootResolver roots,
                                    StandaloneRelationSelector selector, StandaloneRelationPolicy policy,
                                    StandaloneRelationClassifier classifier, EntityManager em, Clock clock,
                                    PlatformTransactionManager transactionManager) {
        this.candidates = candidates; this.audits = audits; this.relations = relations; this.roots = roots;
        this.selector = selector; this.policy = policy; this.classifier = classifier; this.em = em; this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record CandidateView(Long id, Long sourcePostId, Long targetPostId, String sourceText, String targetText,
                                StandaloneRelationCandidateStatus status, double selectionScore, String selectionReason,
                                TelegramChannelPostRelationType proposedType, String reason, Double confidence,
                                int attemptCount, String error, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public Page<CandidateView> list(StandaloneRelationCandidateStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)), Sort.by("id").descending());
        return transaction.execute(tx -> (status == null ? candidates.findAll(pageable) : candidates.findByStatus(status, pageable)).map(this::view));
    }

    public List<StandaloneRelationAudit> audit(long candidateId) {
        return audits.findByCandidateIdOrderByIdAsc(candidateId);
    }

    /** Invalidate stale automatic decisions first, including decisions referring to an edited target. */
    public List<Long> discover(long postId) {
        Set<Long> sourceIds = transaction.execute(tx -> {
            Set<Long> affected = new LinkedHashSet<>();
            affected.add(postId);
            for (StandaloneRelationCandidate candidate : candidates.findActiveConnected(postId)) {
                if (!isCurrent(candidate)) {
                    retractInferredRelation(candidate);
                    transition(candidate, StandaloneRelationCandidateStatus.SUPERSEDED, "system", "Post content or relation root changed");
                    affected.add(candidate.getSourcePost().getId());
                }
            }
            return affected;
        });
        List<Long> created = new ArrayList<>();
        for (Long sourceId : Objects.requireNonNull(sourceIds)) {
            List<Long> ids = transaction.execute(tx -> discoverSource(sourceId));
            if (ids != null) created.addAll(ids);
        }
        return created;
    }

    private List<Long> discoverSource(long postId) {
        TelegramChannelPost source = em.find(TelegramChannelPost.class, postId, LockModeType.PESSIMISTIC_WRITE);
        if (source == null || source.getReplyToTelegramMessageId() != null || source.isArchived()
                || source.getText() == null || source.getText().isBlank()
                || !relations.findAllBySourcePostIdWithTarget(postId).isEmpty()) return List.of();
        List<StandaloneRelationSelector.Selection> selected = selector.select(source);
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            var selection = selected.get(i);
            String hash = policy.inputHash(source, selection.target());
            if (candidates.existsBySourcePostIdAndTargetPostIdAndInputHash(source.getId(), selection.target().getId(), hash)) continue;
            StandaloneRelationCandidate candidate = new StandaloneRelationCandidate();
            candidate.setSourcePost(source); candidate.setTargetPost(selection.target()); candidate.setInputHash(hash);
            candidate.setSourceSnapshot(source.getText()); candidate.setTargetSnapshot(selection.target().getText());
            candidate.setSelectionScore(selection.score()); candidate.setSelectionReason(selection.reason());
            boolean uniqueBest = i == 0 && (selected.size() == 1 || selection.score() - selected.get(1).score() >= .20);
            candidate.setAutoApprovalEligible(uniqueBest && selection.strongIdentity() && StandaloneRelationPolicy.hasChangeMarker(source.getText()));
            candidate.setCreatedAt(now()); candidate.setUpdatedAt(now());
            candidate = candidates.save(candidate);
            appendAudit(candidate, "DISCOVERED", "system", selection.reason());
            created.add(candidate.getId());
        }
        return created;
    }

    public int processPending(int limit) {
        recoverStaleProcessing();
        List<Long> ready = candidates.findReadyIds(StandaloneRelationPolicy.MAX_ATTEMPTS, now(), PageRequest.of(0, Math.max(1, Math.min(20, limit))));
        int processed = 0;
        for (Long id : ready) if (process(id)) processed++;
        return processed;
    }

    public boolean process(long candidateId) {
        StandaloneRelationClassifier.Input input = transaction.execute(tx -> claim(candidateId));
        if (input == null) return false;
        StandaloneRelationClassifier.Result result = classifier.classify(input);
        transaction.executeWithoutResult(tx -> finish(input, result));
        return true;
    }

    private StandaloneRelationClassifier.Input claim(long id) {
        StandaloneRelationCandidate candidate = lock(id);
        if (!Set.of(StandaloneRelationCandidateStatus.PENDING, StandaloneRelationCandidateStatus.FAILED).contains(candidate.getStatus())
                || candidate.getAttemptCount() >= StandaloneRelationPolicy.MAX_ATTEMPTS
                || candidate.getNextAttemptAt() != null && candidate.getNextAttemptAt().isAfter(now())) return null;
        if (!isCurrent(candidate) || !relations.findAllBySourcePostIdWithTarget(candidate.getSourcePost().getId()).isEmpty()) {
            transition(candidate, StandaloneRelationCandidateStatus.SUPERSEDED, "system", "Candidate is stale or a relation already exists");
            return null;
        }
        candidate.setAttemptCount(candidate.getAttemptCount() + 1);
        candidate.setProcessingStartedAt(now()); candidate.setNextAttemptAt(null); candidate.setError(null);
        transition(candidate, StandaloneRelationCandidateStatus.PROCESSING, "system", "Attempt " + candidate.getAttemptCount());
        return new StandaloneRelationClassifier.Input(candidate.getId(), candidate.getSourcePost().getId(), candidate.getTargetPost().getId(),
                candidate.getInputHash(), candidate.getSourcePost().getText(), candidate.getTargetPost().getText(),
                String.valueOf(StandaloneRelationPolicy.date(candidate.getSourcePost())),
                String.valueOf(StandaloneRelationPolicy.date(candidate.getTargetPost())), candidate.getAttemptCount());
    }

    private void finish(StandaloneRelationClassifier.Input input, StandaloneRelationClassifier.Result result) {
        StandaloneRelationCandidate candidate = lock(input.candidateId());
        if (candidate.getStatus() != StandaloneRelationCandidateStatus.PROCESSING || candidate.getAttemptCount() != input.attempt()) return;
        if (!isCurrent(candidate) || !Objects.equals(candidate.getInputHash(), input.inputHash())) {
            transition(candidate, StandaloneRelationCandidateStatus.SUPERSEDED, "system", "Content changed during classification");
            return;
        }
        candidate.setProvider(result.provider()); candidate.setModel(result.model()); candidate.setRawResponse(result.rawResponse());
        candidate.setProposedType(result.type()); candidate.setReason(result.reason()); candidate.setConfidence(result.confidence());
        candidate.setProcessingStartedAt(null);
        appendAudit(candidate, "CLASSIFIER_RESULT", "system", "provider=" + result.provider() + "; model=" + result.model()
                + "; attempt=" + input.attempt() + "\n" + Objects.toString(result.rawResponse(), ""));
        if (!result.success()) {
            candidate.setError(result.error());
            candidate.setNextAttemptAt(candidate.getAttemptCount() < StandaloneRelationPolicy.MAX_ATTEMPTS
                    ? now().plusMinutes(5L * candidate.getAttemptCount()) : null);
            transition(candidate, StandaloneRelationCandidateStatus.FAILED, "system", result.error());
        } else if (!result.related()) {
            transition(candidate, StandaloneRelationCandidateStatus.NO_RELATION, "system", result.reason());
        } else if (policy.mayAutoApprove(candidate, result) && !candidate.getSourcePost().isArchived()) {
            if (canLink(candidate)) {
                createRelation(candidate, result.type(), result.reason(), false);
                transition(candidate, StandaloneRelationCandidateStatus.AUTO_APPROVED, "system", result.reason());
            } else {
                transition(candidate, StandaloneRelationCandidateStatus.SUPERSEDED, "system", "An existing relation has priority");
            }
        } else {
            transition(candidate, StandaloneRelationCandidateStatus.REVIEW_REQUIRED, "system", result.reason());
        }
    }

    public CandidateView approve(long candidateId, TelegramChannelPostRelationType type, String reason, String actor) {
        requireActor(actor); requireReason(reason);
        if (type == null || type == TelegramChannelPostRelationType.UNCLASSIFIED) throw new IllegalArgumentException("Choose a confirmed relation type");
        return transaction.execute(tx -> {
            StandaloneRelationCandidate candidate = lock(candidateId);
            requireReviewable(candidate);
            if (!isCurrent(candidate)) throw new IllegalStateException("Candidate content changed; discover the post again");
            if (!canLink(candidate)) throw new IllegalStateException("A manual, reply or confirmed relation already exists");
            candidate.setProposedType(type); candidate.setReason(reason.strip());
            createRelation(candidate, type, reason.strip(), true);
            transition(candidate, StandaloneRelationCandidateStatus.APPROVED, actor, reason.strip());
            return view(candidate);
        });
    }

    public CandidateView reject(long candidateId, String reason, String actor) {
        requireActor(actor); requireReason(reason);
        return transaction.execute(tx -> {
            StandaloneRelationCandidate candidate = lock(candidateId);
            if (candidate.getStatus() == StandaloneRelationCandidateStatus.AUTO_APPROVED) retractInferredRelation(candidate);
            else requireReviewable(candidate);
            candidate.setReason(reason.strip()); candidate.setNextAttemptAt(null); candidate.setProcessingStartedAt(null);
            transition(candidate, StandaloneRelationCandidateStatus.REJECTED, actor, reason.strip());
            return view(candidate);
        });
    }

    public CandidateView retry(long candidateId, String actor) {
        requireActor(actor);
        return transaction.execute(tx -> {
            StandaloneRelationCandidate candidate = lock(candidateId);
            if (!Set.of(StandaloneRelationCandidateStatus.FAILED, StandaloneRelationCandidateStatus.REVIEW_REQUIRED,
                    StandaloneRelationCandidateStatus.REJECTED, StandaloneRelationCandidateStatus.NO_RELATION).contains(candidate.getStatus())) {
                throw new IllegalStateException("Only failed, reviewed or rejected candidates can be retried");
            }
            if (candidate.getAttemptCount() >= StandaloneRelationPolicy.MAX_ATTEMPTS) throw new IllegalStateException("Three attempts exhausted for this input");
            if (!isCurrent(candidate)) throw new IllegalStateException("Candidate content changed; discover the post again");
            candidate.setNextAttemptAt(null); candidate.setError(null);
            transition(candidate, StandaloneRelationCandidateStatus.PENDING, actor, "Manual retry requested");
            return view(candidate);
        });
    }

    private boolean canLink(StandaloneRelationCandidate candidate) {
        return isCurrent(candidate) && relations.findAllBySourcePostIdWithTarget(candidate.getSourcePost().getId()).isEmpty();
    }

    private boolean isCurrent(StandaloneRelationCandidate candidate) {
        if (!policy.validPair(candidate.getSourcePost(), candidate.getTargetPost())
                || !policy.inputHash(candidate.getSourcePost(), candidate.getTargetPost()).equals(candidate.getInputHash())) return false;
        TelegramChannelPostRootResolution resolution = roots.resolveRoot(candidate.getTargetPost());
        return resolution.resolved() && resolution.rootPost().getId().equals(candidate.getTargetPost().getId());
    }

    private void createRelation(StandaloneRelationCandidate candidate, TelegramChannelPostRelationType type, String reason, boolean admin) {
        TelegramChannelPostRelation relation = new TelegramChannelPostRelation();
        relation.setSourcePost(candidate.getSourcePost()); relation.setTargetPost(candidate.getTargetPost());
        relation.setRelationType(type); relation.setReason(reason);
        relation.setRelationOrigin(admin ? TelegramChannelPostRelationOrigin.ADMIN_CONFIRMED : TelegramChannelPostRelationOrigin.STANDALONE_INFERRED);
        relation.setClassificationStatus(admin ? TelegramChannelPostRelationClassificationStatus.NOT_REQUIRED : TelegramChannelPostRelationClassificationStatus.CLASSIFIED);
        relation.setClassificationConfidence(candidate.getConfidence()); relation.setClassifierVersion(StandaloneRelationClassifier.VERSION);
        relation.setClassificationProvider(candidate.getProvider()); relation.setClassificationModel(candidate.getModel());
        relation.setClassificationInputHash(candidate.getInputHash()); relation.setClassificationRawResponse(candidate.getRawResponse());
        relation.setClassifiedAt(now()); relation.setCreatedAt(now()); relation.setUpdatedAt(now());
        relation = relations.save(relation);
        candidate.setRelationId(relation.getId());
    }

    private void retractInferredRelation(StandaloneRelationCandidate candidate) {
        if (candidate.getRelationId() == null) return;
        relations.findById(candidate.getRelationId()).filter(r -> r.getRelationOrigin() == TelegramChannelPostRelationOrigin.STANDALONE_INFERRED
                && Objects.equals(r.getClassificationInputHash(), candidate.getInputHash())).ifPresent(relation -> {
                    relations.delete(relation);
                    appendAudit(candidate, "RELATION_RETRACTED", "system", "Automatic relation " + relation.getId());
                });
    }

    private void recoverStaleProcessing() {
        for (Long id : candidates.findStaleIds(now().minusMinutes(10), PageRequest.of(0, 20))) {
            transaction.executeWithoutResult(tx -> {
                StandaloneRelationCandidate candidate = lock(id);
                if (candidate.getStatus() != StandaloneRelationCandidateStatus.PROCESSING
                        || candidate.getProcessingStartedAt() == null || candidate.getProcessingStartedAt().isAfter(now().minusMinutes(10))) return;
                candidate.setProcessingStartedAt(null); candidate.setNextAttemptAt(now().plusMinutes(5));
                candidate.setError("Classification lease expired");
                transition(candidate, StandaloneRelationCandidateStatus.FAILED, "system", candidate.getError());
            });
        }
    }

    private StandaloneRelationCandidate lock(long id) {
        StandaloneRelationCandidate snapshot = em.find(StandaloneRelationCandidate.class, id);
        if (snapshot == null) throw new IllegalArgumentException("Candidate not found: " + id);
        // Serialize decisions for all candidates of one source before locking an individual candidate.
        em.lock(snapshot.getSourcePost(), LockModeType.PESSIMISTIC_WRITE);
        em.refresh(snapshot, LockModeType.PESSIMISTIC_WRITE);
        em.refresh(snapshot.getSourcePost()); em.refresh(snapshot.getTargetPost(), LockModeType.PESSIMISTIC_READ);
        return snapshot;
    }

    private void transition(StandaloneRelationCandidate candidate, StandaloneRelationCandidateStatus status, String actor, String detail) {
        candidate.setStatus(status); candidate.setUpdatedAt(now()); candidates.save(candidate);
        appendAudit(candidate, status.name(), actor, detail);
    }

    private void appendAudit(StandaloneRelationCandidate candidate, String action, String actor, String detail) {
        StandaloneRelationAudit audit = new StandaloneRelationAudit();
        audit.setCandidateId(candidate.getId()); audit.setAction(action); audit.setActor(actor);
        audit.setDetail(detail); audit.setCreatedAt(now()); audits.save(audit);
    }

    private void requireReviewable(StandaloneRelationCandidate candidate) {
        if (!Set.of(StandaloneRelationCandidateStatus.PENDING, StandaloneRelationCandidateStatus.REVIEW_REQUIRED,
                StandaloneRelationCandidateStatus.FAILED).contains(candidate.getStatus())) throw new IllegalStateException("Candidate is not awaiting review");
    }
    private void requireActor(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 200) throw new IllegalArgumentException("Audit actor is required");
    }
    private void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 2000) throw new IllegalArgumentException("A reason of 1–2000 characters is required");
    }
    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
    private CandidateView view(StandaloneRelationCandidate candidate) {
        return new CandidateView(candidate.getId(), candidate.getSourcePost().getId(), candidate.getTargetPost().getId(),
                candidate.getSourceSnapshot() == null ? candidate.getSourcePost().getText() : candidate.getSourceSnapshot(),
                candidate.getTargetSnapshot() == null ? candidate.getTargetPost().getText() : candidate.getTargetSnapshot(), candidate.getStatus(), candidate.getSelectionScore(),
                candidate.getSelectionReason(), candidate.getProposedType(), candidate.getReason(), candidate.getConfidence(),
                candidate.getAttemptCount(), candidate.getError(), candidate.getCreatedAt(), candidate.getUpdatedAt());
    }
}
