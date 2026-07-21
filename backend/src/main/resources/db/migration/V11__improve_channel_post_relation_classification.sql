/*
 * Одна пара source → target представляет одну структурную связь.
 * Её семантический тип может изменяться после классификации.
 */
ALTER TABLE telegram_channel_post_relations
DROP CONSTRAINT IF EXISTS uk_channel_post_relation;

ALTER TABLE telegram_channel_post_relations
    ADD CONSTRAINT uk_channel_post_relation_pair
        UNIQUE (
                source_post_id,
                target_post_id
            );


/*
 * Добавляем состояния UNCLASSIFIED и MIXED.
 */
ALTER TABLE telegram_channel_post_relations
DROP CONSTRAINT IF EXISTS chk_channel_post_relation_type;

ALTER TABLE telegram_channel_post_relations
    ADD CONSTRAINT chk_channel_post_relation_type
        CHECK (
            relation_type IN (
                              'UNCLASSIFIED',
                              'UPDATE',
                              'CORRECTION',
                              'CANCELLATION',
                              'MIXED'
                )
            );


/*
 * У ещё не классифицированной связи
 * окончательная причина может отсутствовать.
 */
ALTER TABLE telegram_channel_post_relations
    ALTER COLUMN reason DROP NOT NULL;


/*
 * Полный аудит автоматической классификации.
 */
ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classification_status VARCHAR(30)
        NOT NULL
        DEFAULT 'NOT_REQUIRED';

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classification_provider VARCHAR(100);

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classification_model VARCHAR(150);

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classifier_version VARCHAR(50);

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classification_raw_response TEXT;

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classification_error TEXT;

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classified_at TIMESTAMPTZ;


ALTER TABLE telegram_channel_post_relations
    ADD CONSTRAINT chk_channel_post_relation_classification_status
        CHECK (
            classification_status IN (
                                      'NOT_REQUIRED',
                                      'PENDING',
                                      'CLASSIFIED',
                                      'REVIEW_REQUIRED',
                                      'FAILED'
                )
            );


CREATE INDEX idx_channel_post_relation_classification_status
    ON telegram_channel_post_relations (
                                        classification_status
        )
    WHERE classification_status IN (
        'PENDING',
        'REVIEW_REQUIRED',
        'FAILED'
    );