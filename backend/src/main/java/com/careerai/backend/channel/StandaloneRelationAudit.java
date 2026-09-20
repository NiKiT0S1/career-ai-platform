package com.careerai.backend.channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

@Entity @Table(name = "standalone_relation_audit") @Getter @Setter
public class StandaloneRelationAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long candidateId;
    @Column(nullable = false, length = 30) private String action;
    @Column(nullable = false, length = 200) private String actor;
    @Column(columnDefinition = "TEXT") private String detail;
    @Column(nullable = false) private OffsetDateTime createdAt;
}
