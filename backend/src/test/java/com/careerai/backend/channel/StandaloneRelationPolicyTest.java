package com.careerai.backend.channel;

import com.careerai.backend.semantic.SemanticContentHashService;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneRelationPolicyTest {
    private final StandaloneRelationPolicy policy = new StandaloneRelationPolicy(new SemanticContentHashService());

    @Test void forbidsCrossChannelReplyAndNonPrecedingPairs() {
        TelegramChannelPost old = post(1), newer = post(2);
        assertTrue(policy.validPair(newer, old));
        old.setTelegramChatId(200L);
        assertFalse(policy.validPair(newer, old));
        old.setTelegramChatId(100L); newer.setReplyToTelegramMessageId(1L);
        assertFalse(policy.validPair(newer, old));
        newer.setReplyToTelegramMessageId(null); old.setPostedAt(newer.getPostedAt().plusDays(1));
        assertFalse(policy.validPair(newer, old));
        assertFalse(policy.validPair(newer, newer));
    }

    @Test void changedContentAndReplyProvenanceInvalidateHashes() {
        TelegramChannelPost old = post(1), newer = post(2);
        String before = policy.inputHash(newer, old);
        old.setText("The event was cancelled");
        assertNotEquals(before, policy.inputHash(newer, old));
        String after = policy.inputHash(newer, old);
        newer.setReplyToTelegramMessageId(1L);
        assertNotEquals(after, policy.inputHash(newer, old));
    }

    @Test void autoApprovalRequiresExplicitUnambiguousHighConfidenceSingleType() {
        StandaloneRelationCandidate candidate = new StandaloneRelationCandidate();
        candidate.setAutoApprovalEligible(true);
        assertTrue(policy.mayAutoApprove(candidate, result(TelegramChannelPostRelationType.CANCELLATION, .99, true)));
        assertFalse(policy.mayAutoApprove(candidate, result(TelegramChannelPostRelationType.MIXED, 1, true)));
        assertFalse(policy.mayAutoApprove(candidate, result(TelegramChannelPostRelationType.UPDATE, .97, true)));
        assertFalse(policy.mayAutoApprove(candidate, result(TelegramChannelPostRelationType.UPDATE, 1, false)));
        candidate.setAutoApprovalEligible(false);
        assertFalse(policy.mayAutoApprove(candidate, result(TelegramChannelPostRelationType.UPDATE, 1, true)));
    }

    private StandaloneRelationClassifier.Result result(TelegramChannelPostRelationType type, double confidence, boolean explicit) {
        return new StandaloneRelationClassifier.Result(true, true, type, "reason", confidence, explicit, "test", "test", "{}", null);
    }

    static TelegramChannelPost post(long id) {
        TelegramChannelPost post = new TelegramChannelPost(); post.setId(id); post.setTelegramChatId(100L);
        post.setTelegramMessageId(id); post.setText("Announcement " + id);
        post.setPostedAt(OffsetDateTime.parse("2026-09-01T12:00:00Z").plusDays(id));
        post.setCreatedAt(post.getPostedAt());
        return post;
    }
}
