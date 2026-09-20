# Smart answers and publication history

The answer service retains the full retrieval/generation path for substantive, conditional and combined questions. A router label alone cannot enable a shortcut.

| Execution mode | Requirements | Work avoided |
| --- | --- | --- |
| `DIRECT_ANSWER` | Exact simple greeting/thanks in RU/KZ/EN; GENERAL_CHAT; no source/date flags; bounded same-language plain reply | Embedding and second LLM call |
| `DIRECT_FAQ` | Exactly one active FAQ whose normalized question literally matches; no channel/deadline/history requirement; answer language matches | Embedding and answer generation |
| `STRUCTURED_CHANNEL` | Whitelisted unqualified ALL_MATCHING vacancy/event list, every requested scope present, searchable sources with links, no relations/replies/corrections or unresolved standalone candidates | Embedding and answer generation |
| `GENERATIVE_RAG` | Other factual questions, ambiguous/related posts, compound conditions, publication history | Full grounded path preserved |

Structured output consists of short source previews and Telegram links, with an explicit result limit. Unknown expiry is labelled as unconfirmed. It does not synthesize conditions. A bounded scan checks newer raw publications for corrections outside the selected categories. When the scan cannot cover the selected publication range, the shortcut is refused.

Relation expansion remains bounded to four additional publications per category. When an eligible linked source does not fit, `relationContextComplete=false` is propagated. Both current and historical answer paths then return a localized caution and source links without asserting conditions, quoting an outdated offer or asking the LLM to infer the missing changes. This includes older cancellations that remain effective despite several newer updates. The structured renderer also rejects an incomplete chain.

An unavailable or malformed query router falls back to grounded FAQ/channel retrieval; it does not turn a factual question into an unconstrained general answer. No-result messages are localized. Exact FAQ equality ignores case, repeated spaces and ordinary punctuation; keyword overlap and semantic rank never qualify as an exact match.

If answer generation fails, returns empty content or throws, the response gives a localized explanation and source links instead of unprocessed post snippets. Historical selection and expired sources remain labelled. In particular, an old offer is not quoted as an actionable fallback when its cancellation cannot be synthesized. The FAQ command is suggested without claiming that an arbitrary retrieved FAQ answers the question.

## Query caches

Caffeine provides bounded, concurrent, expire-after-write caches. Successful simultaneous identical loads are coalesced. Failed/null/malformed analysis and failed or invalid embeddings are not cached. Embedding arrays are copied on both cache insertion and return.

| Property | Default |
| --- | --- |
| `careerai.answers.cache.analysis-size` | 1000 |
| `careerai.answers.cache.analysis-ttl-seconds` | 600 |
| `careerai.answers.cache.embedding-size` | 500 |
| `careerai.answers.cache.embedding-ttl-seconds` | 1800 |

Size `0` disables retention. Sizes are limited to 10000 and TTLs to 1–86400 seconds. Queries longer than 8000 characters bypass the caches. Keys contain a SHA-256 digest of the stripped exact query, schema namespace, application calendar day and timezone. Embedding keys also include configured model and dimensions. The day component prevents reuse of relative-date analysis across midnight. No generated answer, FAQ entity, post or retrieval result is cached. `invalidateAll()` is available on both analysis and query-embedding services.

The answer service logs execution mode and elapsed milliseconds without logging answer text. Unit tests assert provider call counts; production latency depends on the configured providers and should be measured using real runtime logs rather than inferred from these tests.

## Phase 7.8: publication time and freshness

`ChannelQueryAnalysis` has independent `timeScope` and `freshnessScope`, plus optional ISO dates `dateFrom`/`dateTo`. Older seven/eight-argument constructors retain `ANY_TIME` + `CURRENT` defaults.

- `TODAY`, `YESTERDAY` and `LAST_7_DAYS` use the configured `careerai.time-zone` Clock. Last seven days means today plus six preceding calendar days.
- `CUSTOM_RANGE` treats both user dates as inclusive, converting to SQL timestamps `[first day start, day after final date start)`. Invalid or incomplete ranges request clarification.
- Time filters use the original publication date, falling back to creation time. Editing an old publication does not make it a new publication in today's results. Event/deadline dates are separate concepts and do not select a publication window.
- `CURRENT` follows active/unknown status and checks `expiresAt` immediately, including before the freshness scheduler runs.
- `EXPIRED` selects expired status or a passed expiry timestamp. `ALL` selects both current and expired publications. Neither includes manual archive or invalid records.
- Timeline queries use a separate metadata/SQL path; they do not depend on expired embedding vectors. Pure timeline requests skip embedding generation. Mixed FAQ questions can still use their FAQ vector.
- An empty timeline query never widens into the latest-current-post fallback. Result count remains bounded (8 relevant / 30 ALL_MATCHING), allocated fairly across categories.
- Explicit relation context can include expired publications and corrections outside the requested date window. Archived/invalid related posts remain excluded. The prompt identifies related posts as context, preserves publication dates and expiry labels, and forbids presenting them as additional date-filter matches or recommending expired offers.

Example expectations:

| Request | Time | Freshness |
| --- | --- | --- |
| «Покажи все вакансии» | ANY_TIME | CURRENT |
| «Актуальные вакансии, опубликованные вчера» | YESTERDAY | CURRENT |
| «Что публиковали вчера?» | YESTERDAY | ALL |
| «Покажи истёкшие вакансии» | ANY_TIME | EXPIRED |
| «Публикации с 1 по 15 сентября 2026 года, включая истёкшие» | CUSTOM_RANGE, 2026-09-01…2026-09-15 | ALL |

## Telegram operation

`telegram.bot.polling-enabled=false` disables polling for isolated admin/testing runs. Command matching accepts the exact command plus an optional bot mention/arguments; `/faqanything` is not `/faq`. `/myid` returns `message.from.id` only in private chat; a group request is directed to the bot's private chat.

Regression coverage includes guarded shortcut selection, duplicate/ambiguous FAQ rejection, RU/KZ/EN list recognition, expiry/archive protection, unresolved correction fallback, cache TTL/size/concurrent loading/day/model invalidation, defensive vector copying, timezone/DST date boundaries, historical relation eligibility, combined category allocation, and isolation of empty historical results from current retrieval.
