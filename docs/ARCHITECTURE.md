# Technical Architecture — Finance App

Version 1.0 · Android-first KMP · status: architectural baseline

## 1. Principles

- Offline first; domain logic pure Kotlin and testable without Android.
- One source of truth: local SQLite/Room DB, repositories read/write through transactions.
- Build the smallest sufficient architecture; **logical layers are not automatically separate Gradle modules**.
- All public domain interfaces explicitly model errors; no silent FX or AI fallback.
- Native inference isolated behind a portable interface; Android implementation in `androidMain`.

## 2. Technology proposals (verify compatibility and pin exact versions)

Kotlin, KMP, Compose Multiplatform + Material 3, Coroutines/Flow, Room KMP/SQLite, kotlinx.serialization, Ktor Client for optional FX, lightweight constructor injection (Koin only if justified), kotlin.test/JUnit, Compose UI tests, Detekt/KtLint, GitHub Actions. Android NDK/JNI for initial llama.cpp spike. Check current release interoperability, license, supported ABIs and platform limitations before generating Gradle files; record actual version choices in `libs.versions.toml` and decision record.

## 3. Intended package/module boundaries

```text
androidApp/               Android entry and platform wiring
shared/
  core/model/             Types and identities
  core/money/             Amount, currency, conversion
  domain/transactions/    Ledger business rules
  domain/budget/          Calendar-month budgets
  domain/analytics/       Reports, trend calculations
  domain/exchange/        Rate policy/ports
  data/database/          Room and migrations
  data/repository/        Implementations
  data/backup/            Import/export
  ai/contract/            Engine DTO/port
  ai/parser/              Schema parse/validation
  ai/inference/           expect/actual or injected Android implementation
  presentation/           Compose screens and state
```

Start with `androidApp` + `shared` if this is sufficient; split modules only when test/build boundaries justify it. Dependency flow: UI → use cases → repository/inference interfaces ← platform adapters. Neither domain nor data format imports Android UI, JNI, or AI SDK dependencies.

## 4. Entities and invariants

- `Transaction(id: UUID, type: INCOME|EXPENSE, amountMinor: Long, sourceCurrency: CurrencyCode, categoryId: UUID, occurredAt: Instant, displayDateTimeZone, note?, source: MANUAL|AI, createdAt, updatedAt, conversionSnapshot)`.
- `ConversionSnapshot(sourceCurrency, baseCurrency, rateDecimal, convertedMinor, effectiveAt, sourceId, roundingPolicy)`; for same-currency, identity snapshot. All converted financial totals use persisted snapshot, never an updated FX cache.
- `Category(id, type, name, archivedAt?)`.
- `MonthlyBudget(yearMonth, currencyCode, limitMinor, updatedAt)`.
- `ExchangeRate(base, quote, decimalRate, effectiveAt, provider, retrievedAt)`.
- `AppSettings(primaryCurrency, zoneId, theme, locale, modelLocation?)`.
- `AiDraft` (optional persisted form), `draftId`, parsed proposals and confirmation state. Avoid retaining sensitive prompts unless strictly needed; default transient drafts.

**Arithmetic:** currency-specific ISO 4217 exponent; integer minor units for persisted amounts; decimal/BigDecimal-equivalent explicit portable decimal conversion with defined HALF_EVEN rounding. Assess feasibility of chosen decimal library on commonMain and iOS. Check overflow and maximum accepted value; never use binary floating point for money or persist decimal exchange rates as Double.

**DB rules:** foreign keys, type/category consistency, UNIQUE idempotency key on draft confirmation, transactional inserts/updates, indexed occurredAt/category/type, Room migration tests, monotonic schema version. Define deletion/archival semantics and restoration semantics. Store only required data; sensitive logs prohibited.

## 5. Currency and budget semantics

- No physical accounts. Virtual aggregate balance in base currency from frozen per-transaction conversions.
- Conversion lookup: primary dated source → cached usable rate (clearly labelled age) → manually entered explicit rate → block/postpone operation if no rate. Avoid implicit rate=1 for distinct currencies.
- Changing app base currency is a controlled operation with confirmed revaluation policy, possibly using original source amounts and preserved rate history; approval required before implementation. Do not silently apply latest rates to old transactions.
- Current-month boundary: app-selected IANA time zone. UTC intervals `[startOfMonth, nextMonth)` computed from zoned boundaries, not naive 30×24-hour assumptions.
- 30 full local days `[startOfToday − 30 days, startOfToday)`; includes empty days. Daily = sum/30; weekly equivalent = sum×7/30; normalized month = sum. Explicitly differentiate current calendar-month spend.
- Refund handling is pending product decision and must be tested before enabling it.

## 6. AI boundary / reliability

`LocalInferenceEngine.loadModel`, `generate`, `unload` operate on an abstract engine. A JSON parser verifies exact schema, allowed enums, positive string amounts, accepted ISO currencies, dates, number of operations and bounded text. Canonical domain validation then derives `AiDraft` proposals with source `AI`. UI requires explicit confirm; idempotent repository insert writes once. No raw model text can execute commands or write directly to DB. Model unavailable, OOM, cancellation, timeout or malformed JSON yields actionable error and manual fallback.

## 7. Backup protocol

Versioned schema with identity/checksum of archive, full referential checks, deterministic serialization, sizes/limits against resource exhaustion, schema migrations, atomic replacement only after user confirmation. JSON can be plaintext, clearly warn users. CSV is one-way export. Use Android Storage Access Framework instead of broad file access permissions.

## 8. Architecture decisions / nonfunctional targets

- ADR-001: KMP shared domain, Android-first UI.
- ADR-002: Room KMP for persistent data, subject to Android/iOS build verification.
- ADR-003: isolate inference; llama.cpp + GGUF is **candidate**, not final runtime.
- ADR-004: no cloud, no user accounts.
- ADR-005: bounded logging and sanitized diagnostics.

Performance budgets: startup and screen interactions to be baselined on test devices; AI p95 draft latency initial *target* ≤5s on representative Android 8+ GB RAM but not asserted feasible. Test peak PSS, multiple inferences, cancellation, temperature and app lifecycle. Release requires explicit device evidence and risk signoff, not just emulator CI.
