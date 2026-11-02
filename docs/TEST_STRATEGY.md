# Test Strategy — Finance App

Version 1.0 · proof-oriented development

## 1. Philosophy

Every requirement maps to automated checks when practical; do not equate a green CI with correctness. Codex must report `PASS`, `FAIL`, `NOT RUN` or `BLOCKED` with actual command/runner and artifact. Never invent test counts or screenshots. A task is not accepted solely because code compiles.

## 2. Test pyramid

| Level | Content | When | Evidence |
|---|---|---|---|
| Static | Formatting, Detekt/lint, dependency/security reviews, compilation | Every change | logs |
| Domain unit | Money, FX, budget calendar boundaries, categories, refunds after decision | Every change | JUnit/ktest report |
| Property | Round-trip/overflow/rounding, idempotency, aggregate consistency | Core changes | random seed, report |
| Repository integration | Room CRUD, migrations, atomic backup restore, concurrent confirms | Data changes | DB tests |
| Compose UI | Transactions, budget, chart, themes, errors, back navigation, font scaling | Feature gate | screenshots + tests |
| E2E Android | Fresh-install APK, offline, killed process, missing model, permission scenarios | Milestones | ADB evidence |
| AI regression | Parser fixtures + independent held-out model prompts | AI changes / new weights | field metrics |
| Physical-device benchmark | PSS, latency, startup, repeated prompts | AI qualification | hardware logs |

## 3. Required deterministic fixtures

- USD/JPY/KWD-style currency exponents and extremes.
- Rate identity, missing rate, stale rate, manual rate, update after posting, conversion rounding.
- Month ends, leap years, zone offset change, DST transitions, day boundaries.
- 30 complete days vs current incomplete day; no-spend days; average per week/month labels.
- Category archive preserves history; delete/edit updates aggregates.
- AI unsupported, missing amount, multi-transaction, malformed JSON, repeated confirmation, cancellation, background interruption, OOM simulation.
- Damaged schema/checksum, references to absent categories, oversize backup, repeated import.

## 4. Acceptance matrix

| Requirement | Minimum evidence |
|---|---|
| PRD-AC-01 | repository integration + Android UI screenshot |
| PRD-AC-02 | idempotent confirmation concurrent integration test |
| PRD-AC-03 | frozen snapshot regression and base-switch decision ADR |
| PRD-AC-04 | local zone/calendar boundary fixtures |
| PRD-AC-05 | exact 30-day known-value golden tests |
| PRD-AC-06 | parser negative tests + UI confirmation E2E |
| PRD-AC-07 | byte-semantic round-trip + no-mutation-on-failure |
| PRD-AC-08 | no-network/no-model UI smoke test |
| PRD-AC-09 | both themes, rotation/font-scaling snapshot/UI checks |
| PRD-AC-10 | clean checkout build + CI artifacts and reproducible APK |

## 5. Quality thresholds (initial proposals)

- All critical scenarios must pass; no release with known data-loss, unconfirmed-write or monetary miscalculation defects.
- 90% branch coverage *target* for pure critical financial domain, not sole acceptance gate.
- AI exact type and amount ≥98% on **independent** benchmark corpus and 0 unconfirmed writes.
- Android 8+ GB performance must be measured before AI shipping. Proposed draft p95 ≤5s is a goal, not a guaranteed SLA.
- CI must not mark device-only tests as PASS when they have not run.

## 6. CI workflows / artifacts

Proposed `.github/workflows/validate.yml`: Gradle assemble, unit tests, formatting/lint, architecture checks, test report uploads. `android-ui.yml`: emulator instrumentation tests and screenshot artifacts. `ai-evaluation.yml`: schema/fixture tests, with on-device evaluation separate. `release.yml`: controlled release APK, hash, changelog, reproducibility metadata; release signing keys stored securely and never committed. No workflow may publish externally by default without consent.

Use Gradle Wrapper checked into repo, dependency lock/version catalog where appropriate, and a CI compatibility matrix only where meaningful. Avoid adding brittle CI stages before first passing build.

## 7. Reporting

```text
Task: FIN-XXX
State: PASS | FAIL | BLOCKED
Commit: <actual hash or NONE>
Build: PASS | FAIL | NOT RUN (command, environment)
Unit tests: PASS/FAIL/NOT RUN (actual count)
Integration: PASS/FAIL/NOT RUN
UI: PASS/FAIL/NOT RUN (screenshot paths)
Device: PASS/FAIL/NOT RUN (model/Android/RAM)
Artifacts: <real paths>
Open risks and decisions: <items>
```

If a check cannot execute (e.g., no 8+ GB device), document an explicit gate and do not invent a substitute.
