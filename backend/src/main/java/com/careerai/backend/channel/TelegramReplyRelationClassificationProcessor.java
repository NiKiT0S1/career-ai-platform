package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Выполняет одну задачу классификации.
 *
 * Сетевой LLM-вызов происходит вне транзакции.
 */

@Service
public class TelegramReplyRelationClassificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(TelegramReplyRelationClassificationProcessor.class);

    private final TelegramChannelPostRelationClassificationStateService stateService;
    private final TelegramReplyRelationClassifier classifier;

    public TelegramReplyRelationClassificationProcessor(
            TelegramChannelPostRelationClassificationStateService stateService,
            TelegramReplyRelationClassifier classifier
    ) {
        this.stateService = stateService;
        this.classifier = classifier;
    }

    public void processRelation(Long relationId) {
        Optional<TelegramRelationClassificationWorkItem> workItemOptional = stateService.claim(relationId);

        if (workItemOptional.isEmpty()) {
            return;
        }

        TelegramRelationClassificationWorkItem workItem = workItemOptional.get();

        TelegramReplyRelationClassification result;

        try {
            result = classifier.classify(workItem.context());
        }
        catch (Exception e) {
            log.error("Unexpected Telegram reply relation classification error. relationId={}", relationId, e);

            result = TelegramReplyRelationClassification.failed(
                                    null,
                                    null,
                                    classifier.classifierVersion(),
                                    null,
                                    "Неожиданная ошибка классификации: " + e.getMessage());
        }

        stateService.complete(workItem, result);
    }
}