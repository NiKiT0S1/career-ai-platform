package com.careerai.backend.channel;

import com.careerai.backend.semantic.SemanticSearchProperties;
import com.careerai.backend.semantic.SemanticDocumentContent;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Array;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Bounded same-channel retrieval. No embedding API requests are made here. */
@Component
public class StandaloneRelationSelector {
    private static final int SEARCH_LIMIT = 100;
    private static final int CANDIDATE_LIMIT = 3;
    private final EntityManager entityManager;
    private final NamedParameterJdbcTemplate jdbc;
    private final SemanticSearchProperties semantic;
    private final TelegramChannelPostRelationRootResolver roots;
    private final StandaloneRelationPolicy policy;
    private final SemanticDocumentContent documents;

    public StandaloneRelationSelector(EntityManager entityManager, NamedParameterJdbcTemplate jdbc,
                                     SemanticSearchProperties semantic, TelegramChannelPostRelationRootResolver roots,
                                     StandaloneRelationPolicy policy, SemanticDocumentContent documents) {
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.semantic = semantic;
        this.roots = roots;
        this.policy = policy;
        this.documents = documents;
    }

    public record Selection(TelegramChannelPost target, double score, boolean strongIdentity, String reason) {}
    record Evidence(double score, boolean strongIdentity, String reason) {}

    public List<Selection> select(TelegramChannelPost source) {
        if (source.getReplyToTelegramMessageId() != null || StandaloneRelationPolicy.date(source) == null
                || source.getTelegramChatId() == null || source.getTelegramMessageId() == null) return List.of();
        List<TelegramChannelPost> recent = entityManager.createQuery("""
                select p from TelegramChannelPost p where p.telegramChatId = :chat
                and p.telegramMessageId < :message and coalesce(p.postedAt,p.createdAt) <= :date
                and coalesce(p.postedAt,p.createdAt) >= :after and p.text is not null and trim(p.text) <> ''
                order by p.telegramMessageId desc
                """, TelegramChannelPost.class).setParameter("chat", source.getTelegramChatId())
                .setParameter("message", source.getTelegramMessageId()).setParameter("date", StandaloneRelationPolicy.date(source))
                .setParameter("after", StandaloneRelationPolicy.date(source).minusDays(120)).setMaxResults(SEARCH_LIMIT).getResultList();
        if (recent.isEmpty()) return List.of();
        List<Long> ids = new ArrayList<>(recent.stream().map(TelegramChannelPost::getId).toList());
        ids.add(source.getId());
        Map<Long, TelegramChannelPostMetadata> metadata = entityManager.createQuery("""
                select m from TelegramChannelPostMetadata m join fetch m.post
                where m.post.id in :ids and m.extractionStatus = 'SUCCESS'
                and m.extractedAt >= coalesce(m.post.editedAt,m.post.createdAt)
                """, TelegramChannelPostMetadata.class).setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.toMap(m -> m.getPost().getId(), Function.identity()));
        List<TelegramChannelPost> vectorPosts = new ArrayList<>(recent);
        vectorPosts.add(source);
        Map<Long, double[]> vectors = existingVectors(vectorPosts);
        Map<Long, Selection> selections = new LinkedHashMap<>();
        for (TelegramChannelPost post : recent) {
            if (!policy.validPair(source, post)) continue;
            Evidence evidence = evidence(source, post, metadata.get(source.getId()), metadata.get(post.getId()),
                    cosine(vectors.get(source.getId()), vectors.get(post.getId())));
            if (evidence.score() < .45) continue;
            TelegramChannelPostRootResolution resolution = roots.resolveRoot(post);
            if (!resolution.resolved() || !policy.validPair(source, resolution.rootPost())) continue;
            TelegramChannelPost root = resolution.rootPost();
            // A candidate matching an intermediate post needs human review against its normalized root.
            boolean directRoot = root.getId().equals(post.getId());
            Selection selection = new Selection(root, evidence.score(), evidence.strongIdentity() && directRoot, evidence.reason());
            selections.merge(root.getId(), selection, (a, b) -> a.score() >= b.score() ? a : b);
        }
        return selections.values().stream().sorted(Comparator.comparingDouble(Selection::score).reversed()
                .thenComparing(s -> s.target().getId())).limit(CANDIDATE_LIMIT).toList();
    }

    Evidence evidence(TelegramChannelPost source, TelegramChannelPost target,
                      TelegramChannelPostMetadata sourceMeta, TelegramChannelPostMetadata targetMeta, double similarity) {
        String sourceTitle = sourceMeta == null ? "" : sourceMeta.getTitle();
        String targetTitle = targetMeta == null ? "" : targetMeta.getTitle();
        double titles = overlap(sourceTitle, targetTitle);
        double texts = overlap(source.getText(), target.getText());
        boolean sameCompany = sourceMeta != null && targetMeta != null && meaningful(sourceMeta.getCompany())
                && normalized(sourceMeta.getCompany()).equals(normalized(targetMeta.getCompany()));
        boolean sameType = sourceMeta != null && targetMeta != null && sourceMeta.getPostType() == targetMeta.getPostType();
        boolean strong = titles >= .65 && sameCompany || texts >= .7;
        double lexical = Math.min(1, Math.max(titles, texts) * .75 + (sameCompany ? .2 : 0) + (sameType ? .05 : 0));
        // Similarity admits a review candidate, but never independently authorizes auto approval.
        double score = Math.max(lexical, similarity >= .86 ? similarity * .75 : 0);
        return new Evidence(score, strong, "titleOverlap=%.3f; textOverlap=%.3f; sameCompany=%s; cosine=%.3f"
                .formatted(titles, texts, sameCompany, similarity));
    }

    private Map<Long, double[]> existingVectors(List<TelegramChannelPost> posts) {
        if (!semantic.isEnabled()) return Map.of();
        Map<Long, String> hashes = posts.stream().collect(Collectors.toMap(TelegramChannelPost::getId, documents::channelHash));
        // This query loads at most 101 existing vectors. A missing/stale vector falls back to metadata.
        return jdbc.query("""
                select e.source_id, e.embedding, e.content_hash from semantic_embeddings e
                join telegram_channel_posts p on p.id = e.source_id
                where e.source_type = 'CHANNEL_POST' and e.source_id in (:ids)
                and e.embedding_model = :model and e.embedding_dimensions = :dimensions
                and e.updated_at >= coalesce(p.edited_at,p.created_at)
                """, Map.of("ids", hashes.keySet(), "model", semantic.getEmbeddingModel(), "dimensions", semantic.getOutputDimensions()), rs -> {
            Map<Long, double[]> values = new HashMap<>();
            while (rs.next()) {
                if (!Objects.equals(hashes.get(rs.getLong("source_id")), rs.getString("content_hash"))) continue;
                Array array = rs.getArray("embedding");
                try {
                    Object[] raw = (Object[]) array.getArray();
                    double[] vector = new double[raw.length];
                    for (int i = 0; i < raw.length; i++) vector[i] = ((Number) raw[i]).doubleValue();
                    values.put(rs.getLong("source_id"), vector);
                } finally { array.free(); }
            }
            return values;
        });
    }

    static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return -1;
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Double.isFinite(a[i]) || !Double.isFinite(b[i])) return -1;
            dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i];
        }
        return aa == 0 || bb == 0 ? -1 : dot / Math.sqrt(aa * bb);
    }

    static double overlap(String a, String b) {
        Set<String> left = tokens(a), right = tokens(b);
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> shared = new HashSet<>(left); shared.retainAll(right);
        if (shared.size() < 2) return 0;
        return 2.0 * shared.size() / (left.size() + right.size());
    }
    private static Set<String> tokens(String text) {
        return Arrays.stream(normalized(text).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() >= 3).filter(t -> !Set.of("для", "или", "это", "все", "the", "and", "with").contains(t))
                .collect(Collectors.toSet());
    }
    private static String normalized(String value) { return Objects.toString(value, "").strip().toLowerCase(Locale.ROOT); }
    private static boolean meaningful(String text) { return text != null && text.strip().length() >= 3; }
}
