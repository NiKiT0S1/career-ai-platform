package com.careerai.backend.channel;

import com.careerai.backend.ai.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Классифицирует подтверждённую Telegram Reply-связь
 * через активный LLM-провайдер.
 */

@Service
public class LlmTelegramReplyRelationClassifier implements TelegramReplyRelationClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmTelegramReplyRelationClassifier.class);

    private final LlmProvider llmProvider;
    private final TelegramRelationClassificationRequestFactory requestFactory;
    private final TelegramReplyRelationClassificationParser parser;

    public LlmTelegramReplyRelationClassifier(
            LlmProvider llmProvider,
            TelegramRelationClassificationRequestFactory requestFactory,
            TelegramReplyRelationClassificationParser parser
    ) {
        this.llmProvider = llmProvider;
        this.requestFactory = requestFactory;
        this.parser = parser;
    }

    @Override
    public TelegramReplyRelationClassification classify(TelegramReplyRelationClassificationContext context) {
        LlmRequest request = requestFactory.create(context);

        LlmResponse response = llmProvider.execute(request);

        TelegramReplyRelationClassification result = parser.parse(response);

        log.info("Telegram reply relation classification completed. sourcePostId={}, targetPostId={}, status={}, relationType={}, confidence={}, provider={}, model={}", context.sourcePostId(), context.targetPostId(), result.status(), result.relationType(), result.confidence(), result.provider(), result.model());

        return result;
    }

    @Override
    public String classifierVersion() {
        return TelegramRelationClassifierVersion.CURRENT;
    }
}