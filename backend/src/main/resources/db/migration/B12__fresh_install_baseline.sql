-- Fresh-install baseline through V12. Historical V1 and V2 both create channel posts; keep their checksums unchanged.
-- V2 is the active post schema; shared foundation references it. Existing versioned databases ignore this baseline.
CREATE TABLE telegram_channel_posts (
                                        id BIGSERIAL PRIMARY KEY,

                                        telegram_chat_id BIGINT NOT NULL,
                                        telegram_message_id BIGINT NOT NULL,

                                        channel_title VARCHAR(255),
                                        channel_username VARCHAR(255),

                                        text TEXT,
                                        raw_update_json TEXT,

                                        posted_at TIMESTAMPTZ,
                                        edited_at TIMESTAMPTZ,

                                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                        CONSTRAINT uk_telegram_channel_post UNIQUE (telegram_chat_id, telegram_message_id)
);
CREATE TABLE telegram_users (
                                id BIGSERIAL PRIMARY KEY,
                                telegram_user_id BIGINT NOT NULL UNIQUE,
                                username VARCHAR(255),
                                first_name VARCHAR(255),
                                last_name VARCHAR(255),
                                language_code VARCHAR(20),
                                is_bot BOOLEAN NOT NULL DEFAULT FALSE,
                                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chat_messages (
                               id BIGSERIAL PRIMARY KEY,
                               telegram_user_id BIGINT REFERENCES telegram_users(id),
                               telegram_chat_id BIGINT NOT NULL,
                               role VARCHAR(30) NOT NULL,
                               text TEXT NOT NULL,
                               telegram_message_id BIGINT,
                               created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE bot_runtime_state (
                                   id BIGSERIAL PRIMARY KEY,
                                   state_key VARCHAR(255) NOT NULL UNIQUE,
                                   state_value TEXT NOT NULL,
                                   updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE import_batches (
                                id BIGSERIAL PRIMARY KEY,
                                source_type VARCHAR(50) NOT NULL,
                                original_file_name VARCHAR(500),
                                status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
                                total_items INTEGER NOT NULL DEFAULT 0,
                                successful_items INTEGER NOT NULL DEFAULT 0,
                                failed_items INTEGER NOT NULL DEFAULT 0,
                                error_message TEXT,
                                started_at TIMESTAMP,
                                completed_at TIMESTAMP,
                                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_sources (
                                   id BIGSERIAL PRIMARY KEY,
                                   source_type VARCHAR(50) NOT NULL,
                                   title VARCHAR(500),
                                   description TEXT,
                                   external_id VARCHAR(500),
                                   source_url TEXT,
                                   import_batch_id BIGINT REFERENCES import_batches(id),
                                   status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                                   created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);



CREATE TABLE documents (
                           id BIGSERIAL PRIMARY KEY,
                           source_id BIGINT REFERENCES knowledge_sources(id),
                           import_batch_id BIGINT REFERENCES import_batches(id),
                           original_file_name VARCHAR(500),
                           stored_file_name VARCHAR(500),
                           mime_type VARCHAR(255),
                           file_size BIGINT,
                           storage_path TEXT,
                           extracted_text TEXT,
                           status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_items (
                                 id BIGSERIAL PRIMARY KEY,
                                 source_id BIGINT REFERENCES knowledge_sources(id),
                                 document_id BIGINT REFERENCES documents(id),
                                 telegram_post_id BIGINT REFERENCES telegram_channel_posts(id),

                                 category VARCHAR(50) NOT NULL,
                                 title VARCHAR(500),
                                 content TEXT NOT NULL,

                                 published_at TIMESTAMP,
                                 valid_from TIMESTAMP,
                                 valid_until TIMESTAMP,

                                 status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                                 freshness_policy VARCHAR(50) NOT NULL DEFAULT 'DEFAULT_TTL',
                                 default_ttl_days INTEGER,
                                 needs_review BOOLEAN NOT NULL DEFAULT FALSE,

                                 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_freshness_rules (
                                           id BIGSERIAL PRIMARY KEY,
                                           category VARCHAR(50) NOT NULL UNIQUE,
                                           default_ttl_days INTEGER,
                                           permanent BOOLEAN NOT NULL DEFAULT FALSE,
                                           description TEXT,
                                           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                           updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO knowledge_freshness_rules (category, default_ttl_days, permanent, description)
VALUES
    ('VACANCY', 30, FALSE, 'Vacancies without explicit deadline are considered relevant for 30 days after publication.'),
    ('INTERNSHIP', 45, FALSE, 'Internships without explicit deadline are considered relevant for 45 days after publication.'),
    ('EVENT', 14, FALSE, 'Events without explicit date are considered relevant for 14 days after publication.'),
    ('MASTERCLASS', 14, FALSE, 'Masterclasses without explicit date are considered relevant for 14 days after publication.'),
    ('DEADLINE', NULL, FALSE, 'Deadlines require explicit date extraction or manual review.'),
    ('FAQ', NULL, TRUE, 'FAQ items are permanent until manually archived.'),
    ('PRACTICE_INFO', NULL, TRUE, 'Practice-related instructions are permanent until manually archived.'),
    ('DOCUMENT', NULL, TRUE, 'Documents are permanent until manually archived.'),
    ('GENERAL_INFO', 90, FALSE, 'General information is considered relevant for 90 days unless marked permanent.');

CREATE INDEX idx_chat_messages_telegram_chat_id ON chat_messages (telegram_chat_id);
CREATE INDEX idx_chat_messages_created_at ON chat_messages (created_at);

CREATE INDEX idx_knowledge_items_category ON knowledge_items (category);
CREATE INDEX idx_knowledge_items_status ON knowledge_items (status);
CREATE INDEX idx_knowledge_items_valid_until ON knowledge_items (valid_until);
CREATE INDEX idx_knowledge_items_published_at ON knowledge_items (published_at);


CREATE INDEX idx_documents_status ON documents (status);
CREATE INDEX idx_knowledge_sources_source_type ON knowledge_sources (source_type);
CREATE TABLE telegram_channel_post_metadata (
                                                id BIGSERIAL PRIMARY KEY,

                                                post_id BIGINT NOT NULL,

                                                post_type VARCHAR(50) NOT NULL DEFAULT 'OTHER',

                                                title VARCHAR(500),
                                                company VARCHAR(500),
                                                technologies TEXT,
                                                level_text VARCHAR(255),
                                                format_text VARCHAR(255),

                                                deadline_text VARCHAR(500),
                                                practice_start_text VARCHAR(255),
                                                practice_end_text VARCHAR(255),

                                                summary TEXT,

                                                is_relevant_for_practice BOOLEAN NOT NULL DEFAULT FALSE,

                                                extraction_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                                                extraction_error TEXT,
                                                extracted_at TIMESTAMPTZ,

                                                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                                CONSTRAINT uk_telegram_channel_post_metadata_post_id UNIQUE (post_id),

                                                CONSTRAINT fk_telegram_channel_post_metadata_post
                                                    FOREIGN KEY (post_id)
                                                        REFERENCES telegram_channel_posts (id)
                                                        ON DELETE CASCADE
);

CREATE INDEX idx_telegram_channel_post_metadata_post_type
    ON telegram_channel_post_metadata (post_type);

CREATE INDEX idx_telegram_channel_post_metadata_extraction_status
    ON telegram_channel_post_metadata (extraction_status);
CREATE TABLE faq_entries (
                             id BIGSERIAL PRIMARY KEY,

                             category VARCHAR(100) NOT NULL,
                             slug VARCHAR(150) NOT NULL,

                             question TEXT NOT NULL,
                             short_answer TEXT NOT NULL,
                             full_answer TEXT NOT NULL,
                             keywords TEXT,

                             priority INTEGER NOT NULL DEFAULT 100,
                             is_active BOOLEAN NOT NULL DEFAULT TRUE,

                             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                             updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                             CONSTRAINT uk_faq_entries_slug UNIQUE (slug)
);

CREATE INDEX idx_faq_entries_category
    ON faq_entries (category);

CREATE INDEX idx_faq_entries_is_active
    ON faq_entries (is_active);

CREATE INDEX idx_faq_entries_priority
    ON faq_entries (priority);
INSERT INTO faq_entries (
    category,
    slug,
    question,
    short_answer,
    full_answer,
    keywords,
    priority,
    is_active
)
VALUES
    (
        'vacancies',
        'internship_and_job_search',
        'Как найти стажировку или работу без опыта / Где искать актуальные вакансии?',
        'Актуальные вакансии и стажировки публикуются в Telegram-канале ЦКиТ. Также Центр помогает с подбором позиций начального уровня.',
        'Центр карьеры и трудоустройства регулярно публикует актуальные вакансии и
    стажировки, а также оказывает поддержку в подборе позиций начального уровня. Вся
    информация размещается в официальном Telegram-канале:
    https://t.me/+OSs_weZngl4zODAy
    Кроме того, Центр сотрудничает с ведущими казахстанскими и международными
    компаниями, которые регулярно предлагают студентам стажировки и вакансии. Ежегодно
    формируется и предоставляется актуальный список партнёрских организаций.',
        'стажировка, работа, вакансии, без опыта, актуальные вакансии, telegram, канал, партнерские организации',
        10,
        true
    ),
    (
        'career_guidance',
        'career_guidance_and_events',
        'Помогаете ли вы с выбором карьерного направления / Проводятся ли карьерные мероприятия?',
        'Да. ЦКиТ проводит карьерные консультации, мастер-классы, mock interview, встречи с HR-специалистами и помогает с резюме.',
        'Да, Центр карьеры и трудоустройства в течение всего учебного года проводит карьерные
    консультации и различные мероприятия. В их числе — мастер-классы, пробные
    собеседования (Mock Interview) с участием ведущих компаний, а также панельные сессии
    с приглашёнными HR-специалистами. В рамках этих активностей студенты получают
    помощь в составлении, анализе и улучшении резюме, а также проходят подготовку к
    реальным интервью.',
        'карьерное направление, карьерные мероприятия, консультации, мастер-класс, mock interview, резюме, HR, собеседование',
        20,
        true
    ),
    (
        'practice',
        'practice_support',
        'Как Центр помогает с поиском и прохождением производственной практики?',
        'ЦКиТ помогает с поиском мест практики через партнёрские организации и публикует предложения по практике в Telegram-канале.',
        'Центр карьеры и трудоустройства оказывает содействие студентам в прохождении
    производственной практики, предусмотренной академическим календарём. В рамках
    данной работы осуществляется рассылка запросов в партнёрские организации с целью
    предоставления мест практики для студентов.
    Ежегодно Центр расширяет базу партнёров, привлекая в среднем не менее 30 новых
    компаний посредством заключения двусторонних договоров и меморандумов о
    сотрудничестве.
    Кроме того, на официальном Telegram-канале Центра регулярно публикуются объявления
    о наборах на производственную практику с актуальными предложениями от
    работодателей.',
        'производственная практика, поиск практики, место практики, партнерские организации, работодатели, telegram',
        30,
        true
    ),
    (
        'practice_documents',
        'practice_required_documents',
        'Какие документы необходимы для оформления практики?',
        'Для оформления практики нужен один из документов: трёхсторонний договор, ходатайство или справка с места работы. После практики отчётность сдаётся в School.',
        'Для оформления производственной практики студенту необходимо предоставить один из
    следующих документов:
    - трёхсторонний договор (между студентом, университетом и организацией);
    - ходатайство — в случае прохождения практики в организации, сотрудничающей с
    AITU;
    - справку с места работы — при условии, что студент официально трудоустроен не
    менее 4 месяцев.
    Центр карьеры и трудоустройства (ЦКиТ) сопровождает административную часть
    процесса, включая оформление и консультирование по вопросам: договоров, ходатайств и
    справок.
    По завершении практики студенту необходимо сдать:
    - отчёт по практике;
    - характеристику от организации;
    - календарный план, подписанный руководителем практики от компании (industrial
    supervisor).
    При этом подготовка отчёта, оформление характеристики, заполнение календарного плана,
    а также вопросы оценивания и итоговой сдачи практики находятся в компетенции
    Школы образовательной программы. По данным вопросам необходимо обращаться к
    своему руководителю практики.',
        'практика, документы, оформление практики, трехсторонний договор, ходатайство, справка с места работы, отчет, характеристика, календарный план, school',
        40,
        true
    ),
    (
        'practice_contracts',
        'company_refuses_university_contract',
        'Что делать, если компания не хочет подписывать договор университета?',
        'Нужно обратиться в ЦКиТ. При необходимости договор компании может быть рассмотрен после проверки Юридическим департаментом университета.',
        'В таком случае студенту необходимо обратиться в Центр карьеры и трудоустройства для
    консультации. При необходимости допускается подписание шаблона договора компании
    при условии его предварительного согласования и проверки Юридическим департаментом
    университета.',
        'компания не подписывает договор, договор университета, договор компании, юридический департамент, согласование договора',
        50,
        true
    ),
    (
        'practice',
        'practice_at_own_workplace',
        'Можно ли проходить практику в своей компании/на работе?',
        'Да, если работа соответствует образовательной программе. Для закрытия практики через работу нужен официальный стаж не менее 4 месяцев и справка с места работы.',
        'Да, студент может пройти производственную практику в компании, в которой он уже
    трудоустроен, при условии, что деятельность организации соответствует образовательной
    программе и направлению подготовки.
    Перед оформлением практики необходимо заранее уведомить руководителя практики от
    университета и согласовать соответствие выполняемой работы требованиям
    образовательной программы и задачам практики.
    Одним из обязательных условий является срок официального трудоустройства не менее 4
    месяцев. В этом случае студенту необходимо предоставить оригинал справки с места
    работы с подписью и печатью организации, с обязательным указанием должности и
    периода работы.
    Если срок трудоустройства составляет менее 4 месяцев, студенту необходимо оформить
    трёхсторонний договор с организацией для прохождения практики.',
        'практика на работе, своя компания, справка с места работы, 4 месяца, трудоустройство, трехсторонний договор',
        60,
        true
    ),
    (
        'practice_letters',
        'university_practice_letter',
        'Что необходимо сделать для получения письма от университета о направлении на практику?',
        'Если компания требует официальное письмо от университета, нужно заполнить заявку с данными студента и компании.',
        'Если принимающая компания требует официальное письмо от университета о
    направлении на практику, студенту необходимо подать заявку, заполнив соответствующую
    форму.
    В заявке указываются:
    - данные студента (ФИО, группа, образовательная программа);
    - информация о компании, в которой планируется прохождение практики:
    - наименование организации;
    - ФИО руководителя;
    - должность руководителя;
    - электронный адрес для направления письма.
    После обработки заявки университет подготавливает и направляет официальное письмо в
    адрес принимающей организации.',
        'письмо от университета, направление на практику, заявка, ФИО, группа, образовательная программа, компания, руководитель, email',
        70,
        true
    ),
    (
        'practice_deadlines',
        'practice_documents_deadline_problem',
        'Что делать, если не успеваю сдать документы вовремя?',
        'Нужно заранее уведомить ЦКиТ и уточнить дальнейшие действия. Продление сроков рассматривается индивидуально при уважительной причине.',
        'В случае невозможности своевременной сдачи документов студенту необходимо заранее
    уведомить Центр карьеры и трудоустройства и уточнить дальнейший порядок дальнейших
    действий.
    Обращаем внимание, что дедлайны по всем документам доводятся до сведения студентов
    не менее чем за один месяц. Информирование осуществляется Центром карьеры и
    трудоустройства через Школы образовательных программ, а также напрямую студентам.
    При наличии уважительной причины возможность продления сроков рассматривается в
    индивидуальном порядке.',
        'не успеваю сдать документы, дедлайн, сроки, продление сроков, уважительная причина, практика',
        80,
        true
    ),
    (
        'dual_education',
        'dual_education',
        'Что такое дуальное обучение и как оно проходит?',
        'Дуальное обучение — это формат, где студент совмещает обучение в университете с практической подготовкой на предприятии-партнёре.',
        'Дуальное обучение — это формат, при котором студент совмещает теоретическое
    обучение в университете с практической подготовкой на предприятии-партнёре.
    В рамках дуального обучения студент осваивает практическую часть на базе предприятия,
    при этом теоретическая часть дисциплины изучается в университете согласно
    расписанию. Допускается прохождение не более трёх дисциплин на базе предприятия в
    течение одного триместра.
    Компании, участвующие в дуальном обучении, как правило, принимают группу студентов
    (в среднем от 10 человек и более), официально сотрудничают с университетом и
    обеспечивают условия для качественной практической подготовки. Также важно, чтобы
    предприятие находилось в транспортной доступности от университета.
    Со стороны предприятия назначается ментор (наставник), который сопровождает
    студентов в процессе обучения. Совместно со Школами образовательных программ
    разрабатываются приложения дуального обучения, в которых детально описываются
    дисциплины, реализуемые в рамках данного формата, а также содержание и структура
    практической подготовки.',
        'дуальное обучение, предприятие, партнер, ментор, дисциплины, триместр, практическая подготовка',
        90,
        true
    ),
    (
        'employment',
        'employment_after_graduation',
        'Помогает ли университет с трудоустройством после выпуска?',
        'Да. Университет публикует вакансии, организует карьерные мероприятия и поддерживает связь с выпускниками после окончания обучения.',
        'Да, Центр карьеры и трудоустройства оказывает поддержку студентам и выпускникам в
    вопросах трудоустройства. Университет регулярно публикует актуальные вакансии,
    стажировки и карьерные возможности от партнёрских компаний, а также организует
    ярмарки вакансий, гостевые лекции, мастер-классы и встречи с работодателями.
    В процессе обучения для студентов проводятся mock interview (пробные собеседования),
    где они получают практический опыт прохождения интервью, учатся грамотно
    презентовать себя и готовиться к реальным собеседованиям. Кроме того, во время
    прохождения практики студенты могут проявить себя и зарекомендовать в компании. В
    случае успешного прохождения практики многие из них получают предложения о
    дальнейшем трудоустройстве.
    Центр карьеры и трудоустройства поддерживает связь с выпускниками и после окончания
    университета. Для удобства взаимодействия действует отдельная почта для обращений
    выпускников: alumni@astanait.edu.kz, через которую можно получить консультации, узнать
    о карьерных возможностях.',
        'трудоустройство после выпуска, выпускники, вакансии, стажировки, ярмарка вакансий, mock interview, alumni, карьерные возможности',
        100,
        true
    ),
    (
        'contacts',
        'career_center_contacts',
        'Куда обращаться студентам и выпускникам по вопросам практики и трудоустройства?',
        'Студенты по вопросам практики обращаются на practice@astanait.edu.kz. Выпускники по вопросам отработки гранта и документов могут писать на alumni@astanait.edu.kz.',
        'Центр карьеры и трудоустройства (ЦКиТ) предоставляет отдельные каналы связи для
    студентов и выпускников, чтобы обеспечить оперативную и качественную поддержку по
    различным вопросам.
    Для выпускников AITU:
    Действует отдельная почта — alumni@astanait.edu.kz
    По данному адресу выпускники могут обращаться по вопросам отработки гранта,
    предоставления необходимых документов, получения консультаций, а также уточнения
    требований и процедур.
    Для студентов AITU:
    По всем вопросам, связанным с учебной и производственной практикой, необходимо
    обращаться на почту — practice@astanait.edu.kz
    ЦКиТ обеспечивает сопровождение и консультирование по указанным направлениям,
    помогая студентам и выпускникам эффективно решать возникающие вопросы.',
        'контакты, куда обращаться, practice@astanait.edu.kz, alumni@astanait.edu.kz, студенты, выпускники, практика, трудоустройство, отработка гранта',
        110,
        true
    ),
    (
        'practice_reporting',
        'practice_grade_and_report',
        'Как узнать оценку за практику? Куда сдавать отчет, характеристику и календарный план? К кому обращаться?',
        'По отчёту, характеристике, календарному плану и оценке за практику нужно обращаться в свою School. ЦКиТ отвечает за поиск мест практики, компании и договоры.',
        'По вопросам:
    выставления оценки за практику;
    сдачи отчета по практике;
    сдачи характеристики;
    сдачи календарного плана;
    проверки отчетной документации —
    обращайтесь в свою Школу образовательных программ (School).
    Центр карьеры и трудоустройства отвечает за:
    подбор и поиск мест практики;
    взаимодействие с компаниями-работодателями;
    оформление двухсторонних и трехсторонних договоров;
    консультации по вопросам организации практики;
    сопровождение процесса прохождения практики в части взаимодействия с
    работодателями.
    Если ваш вопрос касается документов, которые необходимо сдать после прохождения
    практики, или итоговой оценки — обращайтесь в свою Школу образовательных
    программ. Если вопрос связан с поиском места практики, компанией или
    оформлением договора — обращайтесь в Центр карьеры и трудоустройства.',
        'оценка за практику, отчет, характеристика, календарный план, отчетная документация, school, школа, ЦКиТ',
        120,
        true
    ),
    (
        'practice',
        'practice_place_search',
        'Где найти место для прохождения практики?',
        'Если нет места практики, можно обратиться в ЦКиТ. Также нужно следить за предложениями по практике в Telegram-канале.',
        'Если у вас нет места для прохождения практики, обратитесь в Центр карьеры и
    трудоустройства. Мы поможем подобрать компанию из числа партнеров университета и
    окажем содействие в поиске подходящего места практики.
    Также рекомендуем следить за актуальными вакансиями и предложениями по практике в
    нашем Telegram-канале, где регулярно публикуются новые возможности от компаний-партнеров.',
        'где найти место практики, нет места практики, практика, партнеры университета, telegram, компании-партнеры',
        130,
        true
    ),
    (
        'practice',
        'practice_place_found_independently',
        'Что делать, если я уже самостоятельно нашел(а) место практики?',
        'Нужно сообщить об этом в ЦКиТ. Если организация соответствует требованиям университета, оформляется договор на прохождение практики.',
        'Сообщите об этом в Центр карьеры и трудоустройства. Если организация соответствует
    требованиям университета, необходимо оформить договор на прохождение практики.',
        'самостоятельно нашел место практики, нашел компанию, договор, организация, требования университета',
        140,
        true
    )
    ON CONFLICT (slug) DO UPDATE
                              SET category = EXCLUDED.category,
                              question = EXCLUDED.question,
                              short_answer = EXCLUDED.short_answer,
                              full_answer = EXCLUDED.full_answer,
                              keywords = EXCLUDED.keywords,
                              priority = EXCLUDED.priority,
                              is_active = EXCLUDED.is_active,
                              updated_at = NOW();
UPDATE faq_entries
SET short_answer = 'Актуальные вакансии и стажировки публикуются в Telegram-канале ЦКиТ. Также Центр помогает студентам найти позиции начального уровня и сотрудничает с партнёрскими компаниями.',
    keywords = 'стажировка, работа, вакансии, без опыта, актуальные вакансии, где искать вакансии, telegram, канал, партнерские организации, начальный уровень',
    updated_at = NOW()
WHERE slug = 'internship_and_job_search';

UPDATE faq_entries
SET short_answer = 'Да. ЦКиТ проводит карьерные консультации, мастер-классы, пробные собеседования, встречи с HR-специалистами и помогает студентам с резюме.',
    updated_at = NOW()
WHERE slug = 'career_guidance_and_events';

UPDATE faq_entries
SET short_answer = 'ЦКиТ помогает студентам искать места производственной практики через партнёрские организации и публикует актуальные предложения по практике в Telegram-канале.',
    updated_at = NOW()
WHERE slug = 'practice_support';

UPDATE faq_entries
SET short_answer = 'Для оформления практики нужен один из документов: трёхсторонний договор, ходатайство или справка с места работы. Отчёт, характеристику и календарный план после практики нужно сдавать в свою Школу образовательной программы.',
    keywords = 'практика, документы, доки, какие доки, оформление практики, собрать документы, трехсторонний договор, ходатайство, справка с места работы, отчет, характеристика, календарный план, школа образовательной программы',
    updated_at = NOW()
WHERE slug = 'practice_required_documents';

UPDATE faq_entries
SET short_answer = 'Нужно обратиться в ЦКиТ. При необходимости договор компании может быть согласован и проверен Юридическим департаментом университета.',
    updated_at = NOW()
WHERE slug = 'company_refuses_university_contract';

UPDATE faq_entries
SET short_answer = 'Да, если работа соответствует образовательной программе. Для оформления практики через место работы нужен официальный стаж не менее 4 месяцев и справка с места работы.',
    updated_at = NOW()
WHERE slug = 'practice_at_own_workplace';

UPDATE faq_entries
SET short_answer = 'Если компания требует официальное письмо о направлении на практику, студенту нужно заполнить заявку с данными о себе и принимающей организации.',
    updated_at = NOW()
WHERE slug = 'university_practice_letter';

UPDATE faq_entries
SET short_answer = 'Если не успеваешь сдать документы вовремя, нужно заранее уведомить ЦКиТ и уточнить дальнейшие действия. Продление сроков рассматривается индивидуально при уважительной причине.',
    updated_at = NOW()
WHERE slug = 'practice_documents_deadline_problem';

UPDATE faq_entries
SET short_answer = 'Дуальное обучение — это формат, при котором студент совмещает обучение в университете с практической подготовкой на предприятии-партнёре.',
    keywords = 'дуальное обучение, дуалка, дуалку, дуальному, дуалке, предприятие, партнер, ментор, дисциплины, триместр, практическая подготовка',
    updated_at = NOW()
WHERE slug = 'dual_education';

UPDATE faq_entries
SET short_answer = 'Да. Университет публикует вакансии и стажировки, организует карьерные мероприятия, проводит mock interview и поддерживает связь с выпускниками после окончания обучения.',
    updated_at = NOW()
WHERE slug = 'employment_after_graduation';

UPDATE faq_entries
SET short_answer = 'Студенты по вопросам практики обращаются на practice@astanait.edu.kz. Выпускники по вопросам отработки гранта, документов и консультаций могут писать на alumni@astanait.edu.kz.',
    updated_at = NOW()
WHERE slug = 'career_center_contacts';

UPDATE faq_entries
SET short_answer = 'Отчёт, характеристику, календарный план и вопросы оценки за практику нужно сдавать и уточнять в своей Школе образовательной программы. ЦКиТ отвечает за места практики, компании и договоры.',
    keywords = 'оценка за практику, отчет, характеристика, календарный план, отчетная документация, куда сдавать, сдать отчет, сдавать отчет, конец практики, после практики, школа образовательной программы, ЦКиТ',
    updated_at = NOW()
WHERE slug = 'practice_grade_and_report';

UPDATE faq_entries
SET short_answer = 'Если у студента нет места практики, можно обратиться в ЦКиТ. Центр помогает подобрать компанию из числа партнёров университета. Также нужно следить за предложениями в Telegram-канале.',
    updated_at = NOW()
WHERE slug = 'practice_place_search';

UPDATE faq_entries
SET short_answer = 'Если студент самостоятельно нашёл место практики, нужно сообщить об этом в ЦКиТ. Если организация соответствует требованиям университета, оформляется договор.',
    updated_at = NOW()
WHERE slug = 'practice_place_found_independently';
CREATE TABLE semantic_embeddings
(
    id                   BIGSERIAL PRIMARY KEY,
    source_type          VARCHAR(30) NOT NULL,
    source_id            BIGINT NOT NULL,
    content_hash         VARCHAR(64) NOT NULL,
    embedding_model      VARCHAR(100) NOT NULL,
    embedding_dimensions INTEGER NOT NULL,
    embedding            DOUBLE PRECISION[] NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_semantic_embeddings_source
        UNIQUE (source_type, source_id),

    CONSTRAINT chk_semantic_embeddings_source_type
        CHECK (source_type IN ('FAQ', 'CHANNEL_POST')),

    CONSTRAINT chk_semantic_embeddings_dimensions
        CHECK (embedding_dimensions > 0)
);

CREATE INDEX idx_semantic_embeddings_source_type
    ON semantic_embeddings (source_type);

CREATE INDEX idx_semantic_embeddings_source_id
    ON semantic_embeddings (source_id);
ALTER TABLE telegram_channel_posts
    ADD COLUMN freshness_status VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN freshness_reason TEXT,
    ADD COLUMN freshness_checked_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN archive_reason TEXT;

ALTER TABLE telegram_channel_posts
    ADD CONSTRAINT chk_channel_post_freshness_status
        CHECK (
            freshness_status IN (
                                 'ACTIVE',
                                 'UNKNOWN',
                                 'EXPIRED',
                                 'INVALID'
                )
            );

ALTER TABLE telegram_channel_posts
    ADD CONSTRAINT chk_channel_post_archived_state
        CHECK (
            (
                is_archived = FALSE
                    AND archived_at IS NULL
                )
                OR
            (
                is_archived = TRUE
                    AND archived_at IS NOT NULL
                )
            );

CREATE INDEX idx_channel_posts_freshness_and_archive
    ON telegram_channel_posts (
                               is_archived,
                               freshness_status
        );

CREATE INDEX idx_channel_posts_expires_at
    ON telegram_channel_posts (expires_at);
CREATE TABLE telegram_channel_post_relations
(
    id BIGSERIAL PRIMARY KEY,

    source_post_id BIGINT NOT NULL,
    target_post_id BIGINT NOT NULL,

    relation_type VARCHAR(30) NOT NULL,
    reason TEXT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_channel_post_relation_source
        FOREIGN KEY (source_post_id)
            REFERENCES telegram_channel_posts (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_channel_post_relation_target
        FOREIGN KEY (target_post_id)
            REFERENCES telegram_channel_posts (id)
            ON DELETE CASCADE,

    CONSTRAINT chk_channel_post_relation_type
        CHECK (
            relation_type IN (
                              'UPDATE',
                              'CORRECTION',
                              'CANCELLATION'
                )
            ),

    CONSTRAINT chk_channel_post_relation_different_posts
        CHECK (source_post_id <> target_post_id),

    CONSTRAINT uk_channel_post_relation
        UNIQUE (
                source_post_id,
                target_post_id,
                relation_type
            )
);

CREATE INDEX idx_channel_post_relation_source
    ON telegram_channel_post_relations (source_post_id);

CREATE INDEX idx_channel_post_relation_target
    ON telegram_channel_post_relations (target_post_id);
ALTER TABLE telegram_channel_posts
    ADD COLUMN reply_to_telegram_message_id BIGINT;

CREATE INDEX idx_channel_posts_reply_reference
    ON telegram_channel_posts
        (
         telegram_chat_id,
         reply_to_telegram_message_id
            )
    WHERE reply_to_telegram_message_id IS NOT NULL;


ALTER TABLE telegram_channel_post_relations
    ADD COLUMN relation_origin VARCHAR(30)
        NOT NULL
        DEFAULT 'MANUAL';

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classification_confidence
        DOUBLE PRECISION;

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN updated_at TIMESTAMPTZ
        NOT NULL
        DEFAULT NOW();


ALTER TABLE telegram_channel_post_relations
    ADD CONSTRAINT chk_channel_post_relation_origin
        CHECK (
            relation_origin IN (
                                'MANUAL',
                                'TELEGRAM_REPLY',
                                'ADMIN_CONFIRMED',
                                'SYSTEM_BACKFILL'
                )
            );

ALTER TABLE telegram_channel_post_relations
    ADD CONSTRAINT chk_channel_post_relation_confidence
        CHECK (
            classification_confidence IS NULL
                OR (
                classification_confidence >= 0.0
                    AND classification_confidence <= 1.0
                )
            );
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
