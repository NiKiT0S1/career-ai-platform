package com.careerai.backend.semantic;

import com.careerai.backend.channel.TelegramChannelPost;
import com.careerai.backend.channel.TelegramChannelPostFreshnessStatus;
import com.careerai.backend.faq.FaqEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Short JDBC operations, deliberately independent of a JPA persistence context. */
@Repository
public class SemanticLifecycleRepository {
    private final JdbcTemplate jdbc;
    private final SemanticDocumentContent content;

    public SemanticLifecycleRepository(JdbcTemplate jdbc, SemanticDocumentContent content) {
        this.jdbc = jdbc;
        this.content = content;
    }

    public Optional<SemanticDocumentSnapshot> findDocument(SemanticSourceType type, long id, OffsetDateTime now) {
        List<SemanticDocumentSnapshot> documents = switch (type) {
            case CHANNEL_POST -> jdbc.query("""
                    SELECT id, channel_title, channel_username, text, posted_at, edited_at,
                           freshness_status, expires_at, is_archived
                    FROM telegram_channel_posts WHERE id = ?
                    """, (rs, row) -> {
                TelegramChannelPost post = new TelegramChannelPost();
                post.setId(rs.getLong("id"));
                post.setChannelTitle(rs.getString("channel_title"));
                post.setChannelUsername(rs.getString("channel_username"));
                post.setText(rs.getString("text"));
                post.setPostedAt(rs.getObject("posted_at", OffsetDateTime.class));
                post.setEditedAt(rs.getObject("edited_at", OffsetDateTime.class));
                post.setFreshnessStatus(TelegramChannelPostFreshnessStatus.valueOf(rs.getString("freshness_status")));
                post.setExpiresAt(rs.getObject("expires_at", OffsetDateTime.class));
                post.setArchived(rs.getBoolean("is_archived"));
                return new SemanticDocumentSnapshot(content.channelTitle(post), content.channelContent(post),
                        content.channelHash(post), post.isSearchable(now) && hasText(post.getText()));
            }, id);
            case FAQ -> jdbc.query("""
                    SELECT id, category, question, short_answer, full_answer, keywords, is_active
                    FROM faq_entries WHERE id = ?
                    """, (rs, row) -> {
                FaqEntry entry = new FaqEntry();
                entry.setId(rs.getLong("id"));
                entry.setCategory(rs.getString("category"));
                entry.setQuestion(rs.getString("question"));
                entry.setShortAnswer(rs.getString("short_answer"));
                entry.setFullAnswer(rs.getString("full_answer"));
                entry.setKeywords(rs.getString("keywords"));
                entry.setActive(rs.getBoolean("is_active"));
                return new SemanticDocumentSnapshot(entry.getQuestion(), content.faqContent(entry),
                        content.faqHash(entry), Boolean.TRUE.equals(entry.getActive())
                        && hasText(entry.getQuestion()) && hasText(entry.getFullAnswer()));
            }, id);
        };
        return documents.stream().findFirst();
    }

    public List<Long> findIdsAfter(SemanticSourceType type, long cursor, int limit) {
        String sql = switch (type) {
            case CHANNEL_POST -> "SELECT id FROM telegram_channel_posts WHERE id > ? ORDER BY id LIMIT ?";
            case FAQ -> "SELECT id FROM faq_entries WHERE id > ? ORDER BY id LIMIT ?";
        };
        return jdbc.queryForList(sql, Long.class, cursor, limit);
    }

    public Optional<RetryState> findRetry(SemanticSourceType type, long id) {
        return jdbc.query("""
                SELECT generation_key, attempts, retry_after FROM semantic_embedding_retries
                WHERE source_type = ? AND source_id = ?
                """, (rs, row) -> new RetryState(rs.getString("generation_key"), rs.getInt("attempts"),
                rs.getObject("retry_after", OffsetDateTime.class)), type.name(), id).stream().findFirst();
    }

    public void recordFailure(SemanticSourceType type, long id, String generationKey, int attempts,
                              OffsetDateTime retryAfter, String error) {
        jdbc.update("""
                INSERT INTO semantic_embedding_retries
                    (source_type, source_id, generation_key, attempts, retry_after, last_error)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_type, source_id) DO UPDATE SET
                    generation_key = EXCLUDED.generation_key, attempts = EXCLUDED.attempts,
                    retry_after = EXCLUDED.retry_after, last_error = EXCLUDED.last_error, updated_at = NOW()
                """, type.name(), id, generationKey, attempts, retryAfter,
                error == null ? "Embedding generation failed" : error.substring(0, Math.min(500, error.length())));
    }

    public void clearRetry(SemanticSourceType type, long id) {
        jdbc.update("DELETE FROM semantic_embedding_retries WHERE source_type = ? AND source_id = ?", type.name(), id);
    }

    /** Prioritize ineligible vectors regardless of their position in the keyset sweep. */
    public int deleteUnavailable(int limit, OffsetDateTime now) {
        return jdbc.update("""
                DELETE FROM semantic_embeddings WHERE id IN (
                    SELECT embedding.id FROM semantic_embeddings embedding
                    WHERE (source_type = 'CHANNEL_POST' AND EXISTS (
                        SELECT 1 FROM telegram_channel_posts post WHERE post.id = embedding.source_id
                        AND (post.is_archived = TRUE OR post.freshness_status NOT IN ('ACTIVE', 'UNKNOWN')
                            OR post.expires_at <= ? OR post.text IS NULL OR LENGTH(TRIM(post.text)) = 0)))
                       OR (source_type = 'FAQ' AND EXISTS (
                        SELECT 1 FROM faq_entries faq WHERE faq.id = embedding.source_id AND faq.is_active = FALSE))
                    ORDER BY embedding.id LIMIT ?)
                """, now, limit);
    }

    /** No foreign key exists across the two source tables, so remove orphan rows explicitly. */
    public int deleteOrphans(int limit) {
        int deleted = jdbc.update("""
                DELETE FROM semantic_embeddings WHERE id IN (
                    SELECT embedding.id FROM semantic_embeddings embedding
                    WHERE (source_type = 'CHANNEL_POST' AND NOT EXISTS
                        (SELECT 1 FROM telegram_channel_posts post WHERE post.id = embedding.source_id))
                       OR (source_type = 'FAQ' AND NOT EXISTS
                        (SELECT 1 FROM faq_entries faq WHERE faq.id = embedding.source_id))
                    ORDER BY embedding.id LIMIT ?)
                """, limit);
        jdbc.update("""
                DELETE FROM semantic_embedding_retries WHERE (source_type, source_id) IN (
                    SELECT retry.source_type, retry.source_id FROM semantic_embedding_retries retry
                    WHERE (source_type = 'CHANNEL_POST' AND NOT EXISTS
                        (SELECT 1 FROM telegram_channel_posts post WHERE post.id = retry.source_id))
                       OR (source_type = 'FAQ' AND NOT EXISTS
                        (SELECT 1 FROM faq_entries faq WHERE faq.id = retry.source_id))
                    ORDER BY retry.source_type, retry.source_id LIMIT ?)
                """, limit);
        return deleted;
    }

    private boolean hasText(String text) { return text != null && !text.isBlank(); }

    public record RetryState(String generationKey, int attempts, OffsetDateTime retryAfter) { }
}
