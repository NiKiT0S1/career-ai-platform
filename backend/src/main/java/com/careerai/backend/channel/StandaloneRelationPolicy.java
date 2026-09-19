package com.careerai.backend.channel;

import com.careerai.backend.semantic.SemanticContentHashService;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.*;

@Component
public class StandaloneRelationPolicy {
    public static final int MAX_ATTEMPTS = 3;
    private final SemanticContentHashService hashes;

    public StandaloneRelationPolicy(SemanticContentHashService hashes) { this.hashes = hashes; }

    public boolean validPair(TelegramChannelPost source, TelegramChannelPost target) {
        return source != null && target != null && source.getId() != null && target.getId() != null
                && !source.getId().equals(target.getId()) && source.getReplyToTelegramMessageId() == null
                && source.getTelegramChatId() != null && source.getTelegramChatId().equals(target.getTelegramChatId())
                && source.getTelegramMessageId() != null && target.getTelegramMessageId() != null
                && source.getTelegramMessageId() > target.getTelegramMessageId()
                && date(source) != null && date(target) != null && !date(target).isAfter(date(source))
                && hasText(source.getText()) && hasText(target.getText());
    }

    public String inputHash(TelegramChannelPost source, TelegramChannelPost target) {
        // Length-prefixed inputs avoid collisions caused by delimiters appearing in untrusted post text.
        return hashes.calculateHash(StandaloneRelationClassifier.VERSION + part(source) + part(target));
    }

    private String part(TelegramChannelPost post) {
        StringBuilder value = new StringBuilder();
        for (String field : List.of(String.valueOf(post.getId()), String.valueOf(post.getTelegramChatId()),
                String.valueOf(post.getTelegramMessageId()), String.valueOf(post.getReplyToTelegramMessageId()),
                String.valueOf(post.getPostedAt()), String.valueOf(post.getEditedAt()), String.valueOf(post.getText()))) {
            value.append(field.length()).append(':').append(field);
        }
        return value.toString();
    }

    public boolean mayAutoApprove(StandaloneRelationCandidate candidate, StandaloneRelationClassifier.Result result) {
        return candidate.isAutoApprovalEligible() && result.success() && result.related()
                && result.explicitChange() && result.confidence() >= .98
                && Set.of(TelegramChannelPostRelationType.UPDATE, TelegramChannelPostRelationType.CORRECTION,
                          TelegramChannelPostRelationType.CANCELLATION).contains(result.type());
    }

    public static boolean hasChangeMarker(String value) {
        String text = Objects.toString(value, "").toLowerCase(Locale.ROOT);
        return List.of("отмен", "перенос", "перенес", "исправ", "уточнен", "уточнён", "обновлен", "обновлён",
                "продлен", "продлён", "дополнен", "изменен", "изменён", "cancel", "reschedul", "correct",
                "update", "postpon", "extended", "түзет", "өзгер", "тоқтат", "ауыстыр").stream().anyMatch(text::contains);
    }

    public static OffsetDateTime date(TelegramChannelPost post) {
        return post.getPostedAt() != null ? post.getPostedAt() : post.getCreatedAt();
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
