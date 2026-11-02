# Local AI integration and evaluation

Version 1.0 · status: provisional model path

## 1. Model and datasets

**Initial testing:** use a downloadable baseline `Qwen3-4B-Instruct-2507` 4-bit model *after checking* the precise release, authenticity, model license, variant/quantization, architecture, expected inference runtime, tokenizer and chat template. The string `Qwen3-4B-Instruct-2507-4bit` is not a unique model file or guarantee of llama.cpp compatibility. Do not invent URL, file path, hash or runtime support. Record model source, commit/revision, checksum, disk/RAM footprint, terms and conversion steps.

The optional debug adapter accepts separately supplied, exact-hash model assets.
Weights, training data and system prompts are not distributed by this repository.
The accepted artifact identity is described in [manifest](models/day07-iteration4.json).
Runtime availability does not establish model quality or acceptable latency.
Production inference remains disabled pending independent evaluation and device tests.
Keep adapter, prompt template, parser and asset selection independent. Re-evaluate
when changing weights or templates. Use an external, authorized held-out dataset;
never copy private training/audit examples or workstation paths into source or logs.

## 2. Spike — before committing to runtime

1. Inspect host Gradle/Java/SDK/NDK, `adb devices`, architecture, connected devices and dataset path; report exactly what is verified vs missing.
2. Search for a legitimate 4-bit baseline artifact; inspect file provenance and compatibility. Download only from authoritative licensed source, do not commit weights, and disclose storage/network impact first when significant.
3. Validate tokenizer, chat template and prompt format; record expected structured outputs.
4. Build minimal Android inference harness using candidate llama.cpp/GGUF JNI implementation; if variant not compatible, document conversion prerequisites or test alternative supported Android engine. Do not claim model runs without on-device log evidence.
5. Benchmark first-load time, warm-load time, peak Java/native PSS, first-token latency, total time to full JSON draft, cancellation, 10 repeated inferences, battery/thermal signals when measurable.
6. Run 6 GB Pixel 4 XL as a supplementary test, not as 8+ GB representative. Full AI release requires one representative Android 8+ GB arm64 device.
7. Write an ADR with GO / CONDITIONAL GO / NO-GO, device details, model hash, quant config, context length, sampling parameters and reproducible command logs.

Memory estimate: 4B × 4 bits ≈2 GB decimal (1.86 GiB) for ideal weight bits only, plus quant metadata, runtime, KV cache and Android app overhead. Never infer fitness from file size alone.

## 3. Parsed output contract

```json
{
  "schema_version": 1,
  "status": "ready",
  "transactions": [
    {
      "type": "expense",
      "amount": "250.00",
      "currency": "RUB",
      "category_hint": "cafe",
      "date": "2026-11-01",
      "description": "Кофе"
    }
  ],
  "missing_fields": []
}
```

Statuses: `ready`, `needs_clarification`, `unsupported`. `transactions` is bounded (default max 10) and never automatically written. `amount` is decimal string; no float. Text/date handling depends on explicit injected reference date, time zone, base currency and available categories. Infer missing information only where a documented product default applies. Otherwise ask a question. Date and currency in example are **illustrative**, not fixed test expectations for relative expressions.

Strict pipeline: user text → local inference → extract strictly validated JSON → map known categories → calculate rates through deterministic domain → present editable preview → explicit user confirmation → idempotent DB transaction. Reject malformed schema, hallucinated fields, missing amount, inappropriate category, invalid currency/date, multiple drafts beyond bound and impossible rate. Manual entry always available.

## 4. Evaluation

Build a versioned held-out golden dataset with expected normalized draft fields and negative/ambiguous examples. Score field-level accuracy for `type`, `amount`, `currency`, `category`, `date`, transaction count and ability to abstain. Separately track schema compliance, false positive commits (must always be zero by design), latency p50/p95, peak memory, crash/OOM, thermal stability.

Initial proposed target: ≥98% exact match on type and amount in independent corpus; zero unconfirmed writes; 100% fail-safe behavior on invalid/malformed outputs. Latency goal p95 ≤5s for a complete draft on selected 8+ GB device is **provisional**, to be renegotiated based on spike results. Tests using model outputs are probabilistic: use bounded deterministic sampling as possible; maintain separate parser fixtures, end-to-end device evaluations and benchmark reports.

## 5. Operational safety

- Disable model functionality gracefully if model missing, license unaccepted (when applicable), file unreadable or device insufficient.
- Run inference off main thread; support cancellation, lifecycle cleanup and bounded resource consumption.
- Treat model output as untrusted data, not instructions. Do not leak financial text to external APIs or diagnostics.
- Do not download weights automatically or include multi-GB weights in APK without explicit approval.

## 6. Explicit debug generation diagnostics

The native debug UI offers an opt-in diagnostic request with a600-second deadline.
Default generation and model loading remain120seconds; release wiring cannot opt
into the longer deadline. Diagnostics use the same model, prompt, parser and CPU
settings. No context caching, GPU backend or model substitution is implied.

Live measurements contain only numeric phase/counters: preparation, input prefill,
completed input tokens, output token count, time to first token, elapsed time,
post-first-token throughput and process PSS. A missing first-token measurement is
displayed as not yet observed. Native input progress advances after completed
decode batches. Counts do not establish semantic correctness or financial validity.
Normal app metrics are transient and are not written to logs or disk. Explicit
instrumentation experiments use synthetic inputs and may save numeric-only local
reports/screenshots; they do not access the user's ledger. Cancellation waits for
the native worker before releasing resources. Late canceled callbacks are ignored.
