# Third-party components

Gradle Wrapper scripts and JAR were generated with the official Gradle 8.13
distribution (revision `073314332697ba45c16c0a0ce1891fa6794179ff`).
They retain their upstream copyright notices and are licensed under Apache-2.0;
the distribution license is included at `gradle/wrapper/LICENSE`.
Source: https://github.com/gradle/gradle/tree/v8.13.0/platforms/core-runtime/wrapper-main

Kotlin and AndroidX/Compose dependencies are resolved from their upstream
repositories by Gradle; their versions are recorded in `gradle/libs.versions.toml`.
This notice does not relicense the application's source or replace the upstream
dependencies' license/notice files.

FIN-009 uses Kotlin Multiplatform BigNum `com.ionspin.kotlin:bignum:0.3.10`
and `org.jetbrains.kotlinx:kotlinx-datetime:0.6.2`, both Apache-2.0 according to
their published Maven POMs. Sources: https://github.com/ionspin/kotlin-multiplatform-bignum
and https://github.com/Kotlin/kotlinx-datetime. BigNum is used only for exact
integer arithmetic; its BigDecimal rounding implementation is not used.

The frozen ISO 4217 code/minor-unit factual reference is extracted from SIX
List One, publication 2026-09-17. SIX is the ISO 4217 Maintenance Agency and
makes the lists available online free of charge. Source URL/publication/hash
are recorded in `docs/testing/iso4217-minor-units.json`; the ISO standard text,
country descriptions and full source document are not redistributed here.

Qwen and llama.cpp are researched candidates only. No model weights or runtime
sources are redistributed here. Their provenance, published licenses and open
qualification gates must be verified independently for each externally supplied artifact.

FIN-010 uses AndroidX Room `2.8.5`, AndroidX SQLite/Bundled SQLite `2.6.2`,
Kotlin Symbol Processing `2.2.21-2.0.5` (build-time), and kotlinx.coroutines
`1.10.2`. Their resolved Maven POMs specify Apache-2.0. Sources:
https://android.googlesource.com/platform/frameworks/support/
https://github.com/google/ksp and https://github.com/Kotlin/kotlinx.coroutines.
The bundled SQLite engine is distributed under SQLite's public-domain dedication:
https://www.sqlite.org/copyright.html. Upstream dependency license metadata is
retained; this notice does not replace those terms.

FIN-011 adds AndroidX Lifecycle ViewModel/runtime-compose `2.9.4` and
Activity KTX `1.10.1` for Android lifecycle/state wiring. Room-testing `2.8.5`
is JVM-test-only for schema migration verification; Bundled SQLite `2.6.2`
is also used directly by Android test fixtures. Their published Maven POMs
specify Apache-2.0. Source: https://android.googlesource.com/platform/frameworks/support/.
No additional inference runtime or model artifact is bundled.

FIN-016 uses `org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1` and its
core runtime for bounded JSON AST parsing. Published Maven POMs and upstream
license specify Apache-2.0. No serialization compiler plugin is added.
Source: https://github.com/Kotlin/kotlinx.serialization/tree/v1.8.1
License: https://github.com/Kotlin/kotlinx.serialization/blob/v1.8.1/LICENSE.txt

## Optional research inference (FIN-018)

The `-PnativeAi=true` research build statically links llama.cpp/ggml from revision
060b2fea4ed528810ddc91d48fb22b3e4d63eb85, MIT license. Source:
https://github.com/ggml-org/llama.cpp . License copy:
[llama.cpp MIT](docs/licenses/llama.cpp-MIT.txt). Normal builds contain no llama.cpp
binary. No weights, training corpus, or private prompts are included in either APK.

The optional adapter accepts externally supplied model assets identified in
[artifact manifest](docs/models/day07-iteration4.json). Weights, adapter, training
corpus and private prompt are not redistributed. Verify licensing and provenance
independently before obtaining or using a model; source publication grants no model rights.
