# Запуск, обновление и восстановление

Эта инструкция относится к ветке `ChatGPT`. Первое обновление выполняется на **новой базе PostgreSQL** или на восстановленной копии существующей базы. Исходную рабочую базу и исходную папку проекта следует сохранить как точку возврата.

## Требования

- JDK 21.
- PostgreSQL 17 и клиентские утилиты `pg_dump`, `pg_restore`, `createdb`, `psql`.
- Доступ к Maven Central для первой сборки, Telegram Bot API и выбранным AI API для работы бота.
- Публичный HTTPS-адрес для запуска Admin Mini App из Telegram.

Расширение pgvector не нужно: embeddings хранятся в массиве `DOUBLE PRECISION[]`. Maven Wrapper включён в репозиторий. Приложение использует порт 8080 по умолчанию; PostgreSQL остаётся отдельным сервисом. JavaScript/CSS панели включены в backend JAR.

## Сначала сохранить текущую базу

Git-backup сохраняет исходники, но не FAQ, публикации, пользователей, переписку, polling offset и решения администраторов. До первого обновления создать дамп существующей базы. Пример из каталога, предназначенного для резервных копий:

```powershell
pg_dump --host=localhost --port=5432 --username=postgres --password --format=custom --file=careerai-before-upgrade.dump --dbname=careerai_freshness
pg_restore --list careerai-before-upgrade.dump
```

Заменить имя базы и пользователя на реальные. `--password` запрашивает пароль, не помещая его в команду. Проверка списка подтверждает читаемость архива; полноценная проверка — успешное восстановление в отдельную базу. Дамп содержит данные приложения и хранится отдельно от публичного GitHub-репозитория. Ключи и исходные переменные окружения сохранить отдельно.

Для предсказуемого переключения остановить старый экземпляр бота перед финальным дампом/переходом. Два polling-процесса с одним Telegram token одновременно не запускать.

## Подготовить новую базу

Есть два сценария.

### Пустая база для нового запуска

Создать отдельную базу, например `careerai_demo`, с уже существующим пользователем приложения `careerai`:

```powershell
createdb --host=localhost --port=5432 --username=postgres --password --owner=careerai careerai_demo
```

Если роль `careerai` ещё не создана, создать её средствами PostgreSQL и назначить пароль, затем повторить команду. Не использовать это имя как обещание, что роль уже существует.

При старте Flyway подготавливает схему. Для совершенно новой базы применяется `B12__fresh_install_baseline.sql`, затем V13, V14 и V15.

### Копия текущей базы

Создать новую пустую базу, затем восстановить исходный дамп:

```powershell
createdb --host=localhost --port=5432 --username=postgres --password --owner=careerai careerai_demo
pg_restore --host=localhost --port=5432 --username=careerai --password --dbname=careerai_demo --no-owner --no-acl --exit-on-error careerai-before-upgrade.dump
```

Восстановление выполняется **в новую базу**, без `--clean` и без удаления исходных данных. Если пользователь приложения не имеет нужных прав, выполнить восстановление через администратора PostgreSQL и назначить владельца согласованным способом.

Дамп содержит `flyway_schema_history`. Существующая база продолжает свою историю версий: B12 не заменяет уже применённые миграции, новые V13–V15 применяются поверх текущей схемы. Перед первым запуском проверить историю:

```sql
SELECT installed_rank, version, description, type, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

## Зачем нужен B12

В исторических файлах V1 и V2 повторяется `CREATE TABLE telegram_channel_posts`. Их нельзя просто последовательно применить к чистой базе. При этом изменение уже опубликованных V1–V12 нарушило бы контрольные суммы существующей Flyway-истории.

Поэтому исходные V1–V12 сохранены без изменений, а B12 описывает результирующую схему до новой разработки без повторного создания таблицы: V1 без дублирующей таблицы, актуальное определение из V2, затем изменения V3–V12. Он предназначен для **чистых баз**, а не для принудительного переписывания существующей истории.

Не включать `baseline-on-migrate`, не удалять `flyway_schema_history` и не выполнять `repair` вслепую. Ошибка checksum или неожиданная версия требуют проверки конкретной истории базы. Переключение Git-ветки не возвращает схему и данные к прежнему состоянию.

## Окружение приложения

Заполнить [.env.example](../.env.example) и передать значения процессу. Копирование шаблона в `.env` само по себе не загружает его в Spring Boot.

| Переменная | Назначение |
| --- | --- |
| `DB_URL` | JDBC-адрес **новой** базы, например `jdbc:postgresql://localhost:5432/careerai_demo` |
| `DB_USERNAME`, `DB_PASSWORD` | Учётная запись PostgreSQL |
| `TELEGRAM_BOT_TOKEN` | Токен бота; только сервер |
| `GEMINI_API_KEYS` | Gemini API key или разрешённый набор ключей через запятую |
| `GROQ_API_KEY` | Ключ Groq для соответствующего provider/router/fallback; может быть пустым |
| `TELEGRAM_POLLING_ENABLED` | Получение Telegram updates; default `true` |
| `CAREERAI_BACKGROUND_ENABLED` | Фоновая обработка; default `true` |
| `ADMIN_ENABLED` | Admin API/launcher; default `false` |
| `ADMIN_TELEGRAM_USER_IDS` | Разрешённые числовые ID через запятую; default пусто |
| `ADMIN_PUBLIC_URL` | Полный публичный HTTPS-адрес `/admin/`; default пусто |
| `SEMANTIC_SEARCH_ENABLED` | Семантический поиск; default `true` |

На этапе проверки копии установить `TELEGRAM_POLLING_ENABLED=false` и `CAREERAI_BACKGROUND_ENABLED=false`; массовую индексацию/пересчёт при старте оставить выключенными. Изменение environment-параметров обычно требует перезапуска.

При необходимости Spring properties можно переопределять аргументами запуска, например `--careerai.time-zone=Asia/Almaty` или `--llm.provider=GROQ`. Текущие имена моделей находятся в `application.properties`; доступность и квоты зависят от аккаунтов провайдеров. Ограничения API бота отдельны от лимитов Codex/ChatGPT.

## Сборка и запуск

Из каталога `backend`:

```powershell
.\mvnw.cmd verify
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

На Linux/macOS:

```bash
bash ./mvnw --batch-mode verify
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

Для разработки доступен `spring-boot:run` или запуск `BackendApplication` в IntelliJ с теми же переменными окружения. После старта проверить `http://localhost:8080/actuator/health`, журнал миграций и подключение именно к новой базе.

Чтобы проверить интеграцию с PostgreSQL, задать `CAREERAI_INTEGRATION_TESTS=true`, `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`, указывающие на отдельную тестовую базу. CI использует собственный `postgres:17`. В CI нет production token/API keys, polling и фоновые задачи выключены. Реальные вызовы Telegram/Gemini/Groq проверяются отдельно.

## HTTPS и первый показ

Разместить backend на выбранном сервере и проксировать публичный HTTPS-домен к его порту. Панель и API должны оставаться на одном origin; пути `/admin/` и `/api/admin/` должны проходить к приложению. Настроить действующий сертификат и сохранить заголовок `Authorization` при проксировании. Не открывать PostgreSQL в публичный интернет ради работы Mini App.

Настроить admin ID и `ADMIN_PUBLIC_URL`, затем открыть `/admin` из личного Telegram-чата. Инструкция для менеджера — в [admin-panel.md](admin-panel.md). Для обычной работы включить polling и нужные фоновые задачи, убедившись, что старый экземпляр с этим токеном остановлен.

Перед переключением проверить на копии: архив/восстановление, FAQ, самостоятельную отмену, истечение срока, историческую выборку, вход/отказ администратора, отсутствие перезаписи чужой правки и журнал действий. Финальный smoke test выполнить на новых тестовых публикациях канала и на RU/KZ/EN. Успешная сборка не подтверждает, что внешний домен и Telegram уже подключены.

## Возврат к исходной версии

Сохранённые GitHub refs:

| Ref | Commit |
| --- | --- |
| `backup/before-chatgpt-2026-09-19` | `6cdf8a9356ca02021ad025d1bad870f4d6c3e066` |
| `backup/main-2026-09-19` | `b10da1a54240d6c42b327a28e61e7d474d4b90f3` |

`ChatGPT` содержит новые изменения; исходные `main` и Desktop checkout сохранены. Для исходной наиболее полной версии выбрать первый backup-ref, а не более ранний main.

1. Остановить новую версию бота.
2. При необходимости отдельно сохранить дамп новой базы, чтобы не потерять данные после обновления.
3. Клонировать исходники в **новую папку**:

```powershell
git clone --branch backup/before-chatgpt-2026-09-19 --single-branch https://github.com/NiKiT0S1/career-ai-platform.git careerai-original
git -C careerai-original rev-parse HEAD
```

4. Создать новую базу `careerai_restored` и восстановить в неё **дамп до обновления**, аналогично разделу восстановления выше. Не направлять старый код на уже обновлённую базу.
5. Настроить прежние секреты и `DB_URL` этой восстановленной базы, собрать исходную версию и проверить запуск. У старого кода нет новых флагов отключения polling, поэтому новый процесс с тем же токеном уже должен быть остановлен.

Причина отдельной базы: новые таблицы, поля, версии записей и происхождение связей `STANDALONE_INFERRED` не обязаны пониматься старым кодом. Исходный Git backup не содержит дампа PostgreSQL; если дамп ещё не создан, считать откат данных подготовленным нельзя.

Локальный full-history Git bundle можно проверить `git bundle verify <путь-к-файлу>` и клонировать через `git clone <путь-к-файлу> <новая-папка>`. Он восстанавливает Git-историю, но также не содержит базу и секреты.

## Источники для CI

Workflow использует [actions/checkout](https://github.com/actions/checkout), [actions/setup-java](https://github.com/actions/setup-java), [actions/upload-artifact](https://github.com/actions/upload-artifact) и [PostgreSQL service container](https://docs.github.com/en/actions/tutorials/use-containerized-services/create-postgresql-service-containers). Эти ссылки описывают инструменты CI; фактический результат конкретного запуска виден в GitHub Actions.
