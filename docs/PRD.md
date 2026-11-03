# Product Requirements Document — Finance App

Version 1.0 · 2026-11-01 · status: proposed implementation baseline

## 1. Product goal

Build a minimalist Android finance tracker for a small group of independent users. Run offline first; no account, server, bank integration, or financial-data telemetry. Support manually entered income/expense transactions and optionally locally inferred AI drafts with mandatory human confirmation. Prefer high correctness, straightforward UX, reproducible automation and portability of business logic to a future iOS build.

## 2. Users and scope

- Each user installs the app on their own device and owns a separate local dataset; **no multi-user profiles or sharing within the app**.
- Android MVP; Kotlin Multiplatform for shared code; iOS later, not part of MVP acceptance.
- Android API 29+ as provisional minSdk; arm64 prioritized for inference; non-AI core remains usable when model absent.
- Basic Android validation can use Pixel 4 XL (6 GB RAM). AI release target is Android with 8+ GB RAM and must be tested on representative hardware.

### In scope

1. Income/expense CRUD; typed categories; transaction history/filtering.
2. Single *virtual* balance in selected base currency (no bank accounts or transfers).
3. Original-currency transactions and deterministic conversion for reporting.
4. One overall monthly spending budget in base currency; monthly consumption/remaining/overrun.
5. Day/category charts and 30-full-day rolling spending rate.
6. Local AI text chat generating one or more **uncommitted** transaction drafts; edit/confirm/cancel.
7. Offline local DB, versioned backup export/import, CSV export, themes and basic settings.

### Explicitly out of scope

Bank sync, real multi-account balances, transfers, debt management, subscriptions, cloud sync, authentication, multi-user accounts, push notifications, voice entry, OCR, auto-import bank statements, proactive recommendations, iOS release, server-side AI. Do not implement without a product decision.

## 3. Screens / flows

- **Home:** current virtual balance, budget progress, current month expenses, recent entries; entry CTA.
- **Transactions:** paginated or efficient history, filters for date/type/category/currency; add/edit/delete (with confirmation/undo behavior).
- **Budgets & analytics:** overall budget, current month progress, category breakdown, daily spending graph, rolling averages.
- **AI chat:** offline text input, model availability/loading state, preview card(s) for proposed transactions, per-draft edit/confirm/cancel, understandable failure states.
- **Settings:** primary currency, language if supported, system/light/dark theme, model file management, backup export/import, clear-data action.

Compose Material 3; adapt to screen size, font scale and dark/light themes. Empty/loading/error states are mandatory. UI must remain responsive while AI runs.

Owner refinement (2026-11-02): Russian readable dates, for example `22:15 2 ноября
2026`, with calendar/time selection and readable date input. No editable time-zone
or UTC-offset fields. Initial setup captures the device zone; existing settings and
transaction zones remain unchanged. DST overlaps require an explicit first/second
occurrence choice. Provide ten starter expense categories aligned with the local
model taxonomy; preserve user edits/archives/deletions and imported catalogs.
Automatically preselect a uniquely resolved active AI category, keeping it editable.
Owner refinement (2026-11-03): if an AI operation has no explicit time, use the
request-time clock in the saved app zone while preserving its date. Explicit numeric
time has priority; ambiguous times require editing. Keep the chosen time internally
and freeze it across retries.
Owner refinement (2026-11-03, later): show operation dates only in UI, including
AI preview, editor and history; remove clock input/picker. Retain the full internal
timestamp/zone/precision for statistics, backup and posting. Date changes preserve
the hidden clock; no silent DST normalization. Foreign AI drafts expose the existing
manual/local saved-rate workflow and exact base-currency preview before confirm.
Explicit confirmation remains mandatory.

## 4. Financial rules (normative)

- Income and expense amount input MUST be positive; direction is determined by enum, not by negative input.
- Original amount is stored in original currency with its currency-defined fractional-unit scale, never as Float/Double.
- Transaction timestamps include a UTC instant and the user-intended local date/time/zone context; analytics use chosen app zone, with a consistent definition across UI and tests.
- Categories are type-specific; archiving a category must not orphan historical operations.
- All revisions/deletions must update aggregates. Saving an AI draft must be idempotent even if confirmation action is triggered twice.
- The *virtual balance* = sum of income converted into base currency minus sum of expense converted into base currency; it is a ledger calculation, **not** an actual bank account balance.
- Transactions keep source amount/currency AND applied conversion details for reproducible historical totals. Updating current FX cache MUST NOT silently rewrite prior converted totals.
- FX provider is swappable and optional; offline modes include a stored dated rate or user-entered rate; missing rates block base-currency posting and request user action rather than guessing. Define deterministic treatment of new base currency before implementing a settings change; do not rewrite historic source amounts.
- **Monthly budget:** sum all expenses in current calendar month in app's selected time zone; income does not offset spending. Unused limit does not roll over. The budget for month YYYY-MM is in base currency; editing base currency requires explicit conversion policy and tests.
- **Rolling averages:** last 30 *completed local calendar days*, zero-expense days included; daily avg = total/30; weekly equivalent = daily avg × 7; normalized 30-day monthly equivalent = daily avg × 30. Also show current calendar-month actual separately; never label normalized rate as actual calendar-month spend.
- Refunds should use linked adjustment semantics and must have a documented effect on budget and charts before implementation (default candidate: reverse the original eligible expense without creating a negative expense record). This is a decision gate, not a license to invent behavior.

## 5. AI behavior

AI may propose income or expense transactions from free-form text, including multiple transactions in one message. It must not persist anything before explicit user confirmation. Missing amount, currency/date ambiguity, nonsensical output, categories not mapped to existing categories, and invalid dates produce a question or editable flagged preview. A cancellation discards the draft. AI failure does not impede manual entry. No private financial content leaves the device through AI.

Illustrative cases: `Кофе 250 рублей` → expense/RUB/cafe; `Зарплата 120000` → income (currency from confirmed settings/context); `Вчера такси 15 долларов` → previous local calendar date; `Купил продукты на 1800 и кофе на 300` → two drafts; `Потратил на еду` → missing amount, no commit.

## 6. Backup and privacy

- Versioned structured JSON archive includes transactions, categories, budgets, rates needed to reproduce historical totals, settings and schema metadata; checksum validates integrity (checksum alone is **not encryption or authentication**).
- Import is all-or-nothing after full schema/referential validation and user confirmation; first version replaces dataset, not merges. Offer pre-import backup; rejected/malformed import changes nothing.
- Export CSV of transactions with explicit column definitions/encoding; CSV is **not** a restorable backup.
- Financial data stored in app-private storage, backups selected via system file picker; display sensitivity warning. No prompts, ledger amounts or raw personal data in logs, crash reporting or analytics. No unnecessary network permissions; exchange service receives only pair/date.

## 7. Acceptance criteria

- `PRD-AC-01`: add/edit/delete income and expense persists after process restart and immediately updates balance/history/budget.
- `PRD-AC-02`: repeated confirmation of one AI draft produces exactly one transaction.
- `PRD-AC-03`: rates updates do not alter historical stored converted amounts; explicit base-currency change has tested migration/revaluation policy.
- `PRD-AC-04`: budgets use month boundaries and app time zone correctly; income does not reduce expense totals.
- `PRD-AC-05`: rolling averages use exactly 30 completed days including zero days.
- `PRD-AC-06`: no AI draft committed without confirmation; invalid AI output never commits.
- `PRD-AC-07`: backup export/import round trip preserves all canonical records; malformed backup leaves DB untouched.
- `PRD-AC-08`: works without model or network except fresh FX lookup; manual entry fully functional.
- `PRD-AC-09`: light/dark themes, large text, empty screens and screen rotation are usable.
- `PRD-AC-10`: build/release artifacts and test evidence are reproducible.

See `TEST_STRATEGY.md` for validation requirements.

## 8. Outstanding product decisions (escalate)

1. On base-currency change: migrate presentation/reporting vs preserve existing historical base basis; present a decision record before shipping settings change.
2. Refund adjustments: exact reference and net-spend accounting rule.
3. Exchange-rate provider, currency coverage, acceptable staleness and licensing.
4. Languages and localization scope beyond Russian/default system language.
5. App model distribution: user file picker default; any auto-download must be approved, including source and license.
