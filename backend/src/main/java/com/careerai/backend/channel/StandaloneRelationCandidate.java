package com.careerai.backend.channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

/** A proposal is separate from a confirmed relation and cannot affect answers before approval. */
@Entity
@Table(name = "standalone_relation_candidates", uniqueConstraints = @UniqueConstraint(
        name = "uk_standalone_candidate_input", columnNames = {"source_post_id", "target_post_id", "input_hash"}))
@Getter @Setter
public class StandaloneRelationCandidate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_post_id", nullable = false) private TelegramChannelPost sourcePost;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_post_id", nullable = false) private TelegramChannelPost targetPost;
    @Column(nullable = false, length = 64) private String inputHash;
    @Column(nullable = false, columnDefinition = "TEXT") private String sourceSnapshot;
    @Column(nullable = false, columnDefinition = "TEXT") private String targetSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private StandaloneRelationCandidateStatus status = StandaloneRelationCandidateStatus.PENDING;
    @Column(nullable = false) private double selectionScore;
    @Column(nullable = false) private boolean autoApprovalEligible;
    @Column(columnDefinition = "TEXT") private String selectionReason;
    @Enumerated(EnumType.STRING) @Column(length = 30) private TelegramChannelPostRelationType proposedType;
    @Column(columnDefinition = "TEXT") private String reason;
    private Double confidence;
    @Column(length = 100) private String provider;
    @Column(length = 150) private String model;
    @Column(columnDefinition = "TEXT") private String rawResponse;
    @Column(columnDefinition = "TEXT") private String error;
    @Column(nullable = false) private int attemptCount;
    private OffsetDateTime nextAttemptAt;
    private OffsetDateTime processingStartedAt;
    private Long relationId;
    @Column(nullable = false) private OffsetDateTime createdAt;
    @Column(nullable = false) private OffsetDateTime updatedAt;
    // A null boxed version identifies a new entity to Spring Data; Hibernate initializes it on insert.
    @Version @Column(nullable = false) private Long entityVersion;
}
