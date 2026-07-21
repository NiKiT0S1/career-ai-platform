/*
 * Добавляем промежуточное состояние PROCESSING.
 *
 * Оно позволяет нескольким потокам или экземплярам backend
 * не классифицировать одну связь одновременно.
 */
ALTER TABLE telegram_channel_post_relations
DROP CONSTRAINT IF EXISTS
        chk_channel_post_relation_classification_status;

ALTER TABLE telegram_channel_post_relations
    ADD CONSTRAINT
        chk_channel_post_relation_classification_status
        CHECK (
            classification_status IN (
                                      'NOT_REQUIRED',
                                      'PENDING',
                                      'PROCESSING',
                                      'CLASSIFIED',
                                      'REVIEW_REQUIRED',
                                      'FAILED'
                )
            );


/*
 * Предложение LLM хранится отдельно от подтверждённого типа.
 *
 * При низкой уверенности:
 * relation_type остаётся UNCLASSIFIED,
 * а proposed_relation_type хранит предложение модели.
 */
ALTER TABLE telegram_channel_post_relations
    ADD COLUMN proposed_relation_type VARCHAR(30);

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN proposed_reason TEXT;

ALTER TABLE telegram_channel_post_relations
    ADD CONSTRAINT chk_channel_post_relation_proposed_type
        CHECK (
            proposed_relation_type IS NULL
                OR proposed_relation_type IN (
                                              'UNCLASSIFIED',
                                              'UPDATE',
                                              'CORRECTION',
                                              'CANCELLATION',
                                              'MIXED'
                )
            );


/*
 * Поля повторной обработки.
 */
ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classification_attempt_count INTEGER
        NOT NULL
        DEFAULT 0;

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classification_next_attempt_at TIMESTAMPTZ;

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN processing_started_at TIMESTAMPTZ;

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classification_input_hash VARCHAR(64);

ALTER TABLE telegram_channel_post_relations
    ADD CONSTRAINT chk_channel_post_relation_attempt_count
        CHECK (
            classification_attempt_count >= 0
            );


/*
 * Оптимистическая блокировка JPA.
 */
ALTER TABLE telegram_channel_post_relations
    ADD COLUMN entity_version BIGINT
        NOT NULL
        DEFAULT 0;


/*
 * Пересоздаём индекс с учётом PROCESSING.
 */
DROP INDEX IF EXISTS
    idx_channel_post_relation_classification_status;

CREATE INDEX idx_channel_post_relation_classification_status
    ON telegram_channel_post_relations (
                                        classification_status,
                                        classification_next_attempt_at
        )
    WHERE classification_status IN (
        'PENDING',
        'PROCESSING',
        'FAILED',
        'REVIEW_REQUIRED'
    );