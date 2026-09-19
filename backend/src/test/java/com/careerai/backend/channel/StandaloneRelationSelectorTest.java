package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneRelationSelectorTest {
    private final StandaloneRelationSelector selector = new StandaloneRelationSelector(null, null, null, null, null, null);

    @Test void semanticSimilarityAloneOnlyProducesReviewEvidence() {
        var source = StandaloneRelationPolicyTest.post(2);
        var target = StandaloneRelationPolicyTest.post(1);
        source.setText("Другая публикация"); target.setText("Unrelated content");
        var evidence = selector.evidence(source, target, null, null, .99);
        assertTrue(evidence.score() >= .45);
        assertFalse(evidence.strongIdentity());
    }

    @Test void oneSharedCompanyDoesNotEstablishAnnouncementIdentity() {
        var source = StandaloneRelationPolicyTest.post(2);
        var target = StandaloneRelationPolicyTest.post(1);
        var a = new TelegramChannelPostMetadata(); a.setCompany("Example"); a.setTitle("Java стажер");
        var b = new TelegramChannelPostMetadata(); b.setCompany("Example"); b.setTitle("Marketing event");
        var evidence = selector.evidence(source, target, a, b, -1);
        assertTrue(evidence.score() < .45);
        assertFalse(evidence.strongIdentity());
    }

    @Test void rejectsBadVectorsAndRequiresMultipleMatchingTokens() {
        assertEquals(-1, StandaloneRelationSelector.cosine(new double[]{Double.NaN}, new double[]{1}));
        assertEquals(-1, StandaloneRelationSelector.cosine(new double[]{0}, new double[]{1}));
        assertEquals(-1, StandaloneRelationSelector.cosine(new double[]{1, 2}, new double[]{1}));
        assertEquals(1, StandaloneRelationSelector.cosine(new double[]{1, 2}, new double[]{2, 4}), .000001);
        assertEquals(0, StandaloneRelationSelector.overlap("Java", "Java"));
    }
}
