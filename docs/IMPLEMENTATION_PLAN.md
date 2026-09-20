# CareerAI — delivery through the admin panel

Scope: Smart Answer Execution, Phase 7.6.4, Phase 7.7, Phase 7.8 and the Telegram Mini App administration panel. CV analysis and production-scale queues are outside this delivery.

## Preserved baseline

- Latest original code: `6cdf8a9356ca02021ad025d1bad870f4d6c3e066` (51 commits).
- Original main: `b10da1a54240d6c42b327a28e61e7d474d4b90f3` (42 commits).
- GitHub backup branches: `backup/before-chatgpt-2026-09-19` and `backup/main-2026-09-19`.
- All new work is on `ChatGPT`; the original Desktop checkout remains untouched.
- A verified full-history Git bundle and a ZIP snapshot were created before implementation. Database contents and secret environment variables are separate from Git backups.

## Milestones and acceptance

- [x] Smart execution: one LLM call for general chat; exact FAQ path; conservative structured listing; full RAG for complex questions; bounded caches; no freshness bypass; unit tests.
- [x] Standalone relations: bounded candidates from the same channel, recorded evidence, conservative approval, review/retry and protection of manual decisions.
- [x] Embedding lifecycle: stale vector exclusion; regeneration/removal after edits, expiry, archive and restore; bounded reconciliation with retry/backoff.
- [x] Hardening: time/freshness query filters; isolated PostgreSQL migration and integration tests; bounded jobs; safe Telegram output; documented startup.
- [x] Admin panel: signed Telegram authentication, explicit administrator allowlist, posts and FAQ management, relation review, maintenance jobs and audit trail; desktop/mobile UI; no credentials in the browser.
- [x] Delivery: tests and build pass, review findings resolved, checkpoints pushed, restore/run/demo instructions updated.

Record concrete test results and limitations in `VALIDATION.md`. Never label untested external integrations as verified. Run development tests against an isolated database; do not migrate the user's existing databases.
