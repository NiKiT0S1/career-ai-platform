package com.careerai.backend.channel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface StandaloneRelationCandidateRepository extends JpaRepository<StandaloneRelationCandidate, Long> {
    boolean existsBySourcePostIdAndTargetPostIdAndInputHash(long source, long target, String hash);

    @EntityGraph(attributePaths = {"sourcePost", "targetPost"})
    Page<StandaloneRelationCandidate> findByStatus(StandaloneRelationCandidateStatus status, Pageable pageable);

    @Override @EntityGraph(attributePaths = {"sourcePost", "targetPost"})
    Page<StandaloneRelationCandidate> findAll(Pageable pageable);

    @Query("""
        select c from StandaloneRelationCandidate c join fetch c.sourcePost join fetch c.targetPost
        where (c.sourcePost.id = :postId or c.targetPost.id = :postId)
        and c.status not in ('SUPERSEDED', 'REJECTED', 'NO_RELATION', 'APPROVED')
        """)
    List<StandaloneRelationCandidate> findActiveConnected(@Param("postId") long postId);

    @Query("""
        select c.id from StandaloneRelationCandidate c
        where c.status in ('PENDING', 'FAILED') and c.attemptCount < :maxAttempts
        and (c.nextAttemptAt is null or c.nextAttemptAt <= :now) order by c.id
        """)
    List<Long> findReadyIds(@Param("maxAttempts") int maxAttempts, @Param("now") OffsetDateTime now, Pageable pageable);

    @Query("select c.id from StandaloneRelationCandidate c where c.status = 'PROCESSING' and c.processingStartedAt < :before order by c.id")
    List<Long> findStaleIds(@Param("before") OffsetDateTime before, Pageable pageable);

    @Query("""
        select (count(c) > 0) from StandaloneRelationCandidate c
        where (c.sourcePost.id in :postIds or c.targetPost.id in :postIds)
        and c.status in ('PENDING', 'PROCESSING', 'REVIEW_REQUIRED', 'FAILED')
        """)
    boolean hasUnresolvedForPostIds(@Param("postIds") Collection<Long> postIds);
}
