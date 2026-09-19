package com.careerai.backend.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StandaloneRelationAuditRepository extends JpaRepository<StandaloneRelationAudit, Long> {
    List<StandaloneRelationAudit> findByCandidateIdOrderByIdAsc(long candidateId);
}
