# Backlog №2 — переход к ошибке

Дата: 2026-11-07. Статус: реализовано и локально проверено; до успешных CI
и merge не завершено в main. Включение этой записи в main через PR завершает пункт.

План: добавить regression UI RED; реализовать прокрутку к полю первой ошибки
существующей валидации; проверить повторное Save, остальные поля, обе темы
и крупный шрифт; выполнить обязательные host/device проверки и review;
опубликовать PR и слить только после CI.

Минимальный подход: Compose BringIntoViewRequester для полей редактора,
счётчик отклонённых попыток сохранения для повторов одинаковой ошибки.
Порядок и финансовые правила buildEntry не меняются. При нескольких ошибках
первой остаётся ошибка, которую возвращает существующий валидатор.
Общий статус и inline-ошибка суммы сохраняются. Фокус и клавиатура автоматически
не открываются. Ошибка хранилища не привязывается к произвольному полю.

Критерии: после Save соответствующее поле видно без тестовой прокрутки;
повторная попытка после ручной прокрутки возвращает к той же ошибке;
ошибки не создают записей; исправленный ввод сохраняется.

## Наблюдения проверки

- RED первого теста: 1/1 FAIL на невидимой сумме после Save; после реализации 1/1 PASS.
- Первый LedgerUiTest прогон: fixture DST наследовала offset UTC при смене зоны;
  ожидала TIME_AMBIGUOUS, получила INVALID_TIME. Исправлена только fixture (offset=null).
- Независимый review выявил P2: повторное открытие IME у другого поля могло запускать
  возврат к старой ошибке. Отдельный real-IME тест добавлен для проверки этого сценария.

Сценарии открытия и закрытия IME после выбора другого поля прошли и на первой
реализации: гонка не воспроизведена на этом AVD, RED→GREEN для неё не заявляется.
Тем не менее постоянный триггер imeBottom удалён: финальный обработчик ждёт
закрытия IME только в рамках новой попытки Save и выполняется однократно.
Перед прокруткой он проверяет актуальную ошибку, чтобы не вернуть к уже
исправленному полю. Триггер одинакового повторного Save остаётся счётчиком.

Финансовые правила, приоритет ошибок, schema, сохранение, AI и зависимости не менялись.
Review: одно P2 обработано ограничением времени жизни перехода; повторный
read-only review финального diff подтвердил устранение, новых замечаний нет.

## Команды проверки

Локально: Temurin 17.0.21+9, существующие SDK/AVD и Gradle cache проекта.
Для core-прогона ANDROID_HOME/ANDROID_SDK_ROOT отключены. Все Gradle-команды
исполнены с --offline --no-daemon, host-suite дополнительно с --rerun-tasks.

```sh
git fetch origin
git switch -c codex/backlog-002-reveal-error origin/main
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.finance.android.LedgerUiTest#failedSaveRevealsAmountAgainAfterManualScrollWithoutPosting
./gradlew :androidApp:ktlintFormat :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.finance.android.LedgerUiTest
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.finance.android.KeyboardVisibilityTest#focusingAnotherFieldAfterFailedSaveKeepsItAboveKeyboard
./gradlew :androidApp:ktlintFormat :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.finance.android.LedgerUiTest,app.finance.android.KeyboardVisibilityTest
./gradlew -PcoreOnly=true :shared:jvmTest :shared:ktlintCheck --rerun-tasks
./gradlew :shared:allTests :shared:ktlintCheck :androidApp:ktlintCheck :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --rerun-tasks
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check_boundaries.py
git diff --check
git diff --exit-code -- shared/schemas
git ls-files --others --exclude-standard -- shared/schemas
```

AVD запущен с -avd Finance_API_36_8GB -port 5556 -read-only
-no-snapshot-save -no-window -no-audio, API36 arm64, 8 GiB.
Тесты используют отдельные случайно именованные Room-базы и синтетические записи.
Финальный targeted suite: 22 PASS / 0 FAIL / 0 SKIP.
Логи: build/reports/backlog-002/; исходный RED XML: red-results/,
финальный targeted XML: targeted-results/. Не включены в Git.

## Host-результаты

PASS: portable core 188; shared allTests 188 JVM + 92 debug + 92 release;
app unit 50; Python 12. Везде 0 failures/errors/skips. Повторные JVM-прогоны
не суммируются в число уникальных сценариев. Android baseline: 176 tasks executed.
Ktlint, Android lint, build, source/packaged-manifest privacy и schema diff PASS.
Обычный APK без native AI: androidApp/build/outputs/apk/debug/androidApp-debug.apk.
SHA-256: ba3e2120630690b7863ea6916d3240d92b9de6ef2035ab30144c9ae06ccee7e2.

Дополнительно исполнены:
```sh
python3 scripts/check_boundaries.py --manifest androidApp/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml
adb -s emulator-5556 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s emulator-5556 install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r -e saveProof true app.finance.android.test/androidx.test.runner.AndroidJUnitRunner
shasum -a 256 androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

NOT RUN: physical Pixel, API29, полноценный TalkBack, real-model inference;
они не являются acceptance этого изменения. Нет заявлений о готовности AI.
Большие артефакты и приватные журналы не публикуются. Существующий Validate CI
публикует android-validation с APK и host reports; его результаты проверяются
для точного PR head перед merge. Локальный APK hash не заявляет byte-identical CI.

## Первый полный device-прогон

102 selected: 96 PASS / 4 SKIP / 2 FAIL. Сохранён full-ui.log.
Оба отказа в существующем RateCacheUiTest до Save:
pairAndTimeChangesClearChoiceAndMissingCacheNeverGuesses не нашёл fx_use;
cacheFailureDoesNotPreventExplicitManualEntry ожидал STORAGE_FAILURE, получил null.
В этих местах editorSaveAttempt=0 и новая прокрутка не запускается.
Изолированный повтор первого сценария на том же APK: PASS 1/1, 6.5s.
Полный повтор воспроизвёл те же два отказа (full-ui-repeat.log); RateCacheUiTest
отдельно дал один отказ. Проверка геометрии перед кликом установила причину:
assertIsDisplayed принимал частично видимую кнопку (33 из 105 px высоты),
но клик по центру не попадал в неё; в другом сценарии видимая высота была 0.
Диагностический прогон сохранён в fx-geometry-red.log.

Исправлен только bring() в RateCacheUiTest: элемент с прокручиваемым предком
всегда получает performScrollTo() перед реальным кликом; неподвижные кнопки
Save/Cancel остаются без прокрутки. Финансовый код и проверки результата не менялись.
Первая попытка помощника с обязательным ожиданием полной высоты была отклонена:
не все элементы имеют scroll-предка, а строгая геометрия дала timeout.
Эти отказы сохранены в fx-geometry-green.log, название не означает PASS.
Финальный RateCacheUiTest: 3 PASS / 0 FAIL (fx-scroll-green.log).

После изменения тестового помощника повторены ktlint, unit/shared suite, lint
и сборка обоих APK: PASS, 6 tasks executed / 170 up-to-date (final-build.log).
Фактические host-тесты ранее выполнены с --rerun-tasks; кешированный повтор
не представлен как новый независимый прогон.


## Финальный device-прогон и checklist

Полный instrumentation после исправления помощника: **98 PASS / 4 SKIP / 0 FAIL**,
102 selected, 198.13 секунды (full-ui-final.log). Это обычная сборка: три opt-in
real-model сценария и один native-library-only сценарий пропущены явно.
Все новые сценарии и RateCacheUiTest прошли в составе полного набора.

Скриншоты PNG 1080×2400 визуально просмотрены:
artifacts/screenshots/backlog-002/backlog-002-amount.png,
backlog-002-large-false.png, backlog-002-large-true.png.
Поле ошибки, общий статус и кнопки видимы, обе темы при fontScale=2 проверены.

- [x] Первая ошибка существующего валидатора раскрывает соответствующее поле.
- [x] Повтор одинакового Save после ручной прокрутки снова раскрывает ошибку.
- [x] Отказы не создают записей; исправленный ввод сохраняет точные 100 minor.
- [x] Клавиатура и последующий фокус другого поля не возвращают к старой ошибке.
- [x] Обе темы, шрифт 200%, DST-варианты, все группы ошибок проверены на AVD.
- [x] Formatter, host tests, build, lint, boundaries, schema, полный UI и review.
- [x] Diff не содержит пользовательских данных, весов, секретов и локальных home paths.

## Интеграция

PR проходит существующий Validate (core и android); проверяется финальный head.
CI компилирует instrumentation APK, device-прогон подтверждён локально выше.
Публикация через GitHub connector с проверкой совпадения tree с локальным
коммитом; Git push credential отсутствует. Локальный checkpoint сохраняется.
Merge выполняется только после успешных проверок, с expected head SHA;
reset/force-push не используются. Фактические head, merge SHA, CI и артефакт
android-validation доступны в PR, включившем этот отчёт в main.
Следующий пункт автоматически не запускается.
