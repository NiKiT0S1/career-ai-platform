package com.careerai.backend.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {
    private final JdbcTemplate jdbc;
    public AdminAuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void record(long actor, String action, String entity, Long id, String details) {
        jdbc.update("INSERT INTO admin_audit_events(actor_telegram_id,action,entity_type,entity_id,details) VALUES (?,?,?,?,?)",
                actor, action, entity, id, details == null ? "" : details);
    }
}
