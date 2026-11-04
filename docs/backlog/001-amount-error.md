# Backlog №1 — ошибка суммы рядом с полем

Дата: 2026-11-04. Статус реализации: локальные проверки PASS. Интеграция: [PR #1](https://github.com/Vitazine/finance-app/pull/1).
До merge — реализовано, ожидает слияния; после подтверждённого merge — завершено в main.

## Объём и критерии

В ручном редакторе показывать существующую причину INVALID_AMOUNT или
AMOUNT_OVERFLOW под суммой и сохранять общий статус формы. Использовать
Material 3 supportingText/isError и конкретное доступное сообщение.
Финансовую валидацию, порядок проверок и сохранение не менять.
После изменения ввода ошибка очищается существующим механизмом формы.
Ошибки других полей не должны подсвечивать сумму.

## План

- [x] Проверить main, чистоту рабочего дерева и отсутствие открытого PR.
- [x] Подтвердить отсутствие inline-сообщения в текущем редакторе.
- [x] Выполнить регрессионный UI-тест до изменения реализации (RED).
- [x] Добавить минимальное отображение и получить GREEN.
- [x] Выполнить core/Android/Python/boundaries, UI и review.
- [x] Сохранить команды, counts, SHA-256 APK и ограничения.
- [x] Создать [PR #1](https://github.com/Vitazine/finance-app/pull/1).
- CI и завершение в main определяются проверками и статусом merge этого PR; наличие ветки само по себе не означает завершение.

UI-проверка использует отдельную случайно именованную Room-базу с синтетической
категорией; проверяет нулевую сумму, переполнение, исправление ввода, обе темы,
отсутствие записей при отказе и одну точную запись после успешного сохранения.
Удаление inline-сообщения или его доступной семантики должно ломать этот тест.

## Изменения

`LedgerField` получает необязательное сообщение ошибки: оно заменяет подсказку
в supportingText, включает isError и доступную Error semantics. Только поле суммы
редактора передаёт INVALID_AMOUNT/AMOUNT_OVERFLOW. Общий editor_error сохранён.
Существующий механизм изменения полей очищает обе формы сообщения.
Новые зависимости, ресурсы, финансовые правила, schema и модель не добавлены.

## Команды

В локальном окружении использованы Temurin 17.0.21+9, проектные Android SDK и
Gradle cache; для portable-прогона Android SDK environment отключён.
`--offline` использует уже установленный кеш зависимостей.

```sh
git fetch origin
git switch -c codex/backlog-001-amount-error origin/main
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --offline --no-daemon
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.finance.android.LedgerUiTest#amountErrorAppearsAtFieldAndClearsAfterCorrectionInBothThemes --offline --no-daemon
./gradlew :androidApp:ktlintFormat :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.finance.android.LedgerUiTest#amountErrorAppearsAtFieldAndClearsAfterCorrectionInBothThemes -Pandroid.testInstrumentationRunnerArguments.saveProof=true --offline --no-daemon
./gradlew -PcoreOnly=true :shared:jvmTest :shared:ktlintCheck --rerun-tasks --offline --no-daemon
./gradlew :shared:allTests :shared:ktlintCheck :androidApp:ktlintCheck :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --offline --no-daemon
./gradlew :shared:allTests --rerun-tasks --offline --no-daemon
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check_boundaries.py
python3 scripts/check_boundaries.py --manifest androidApp/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml
adb -s emulator-5556 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s emulator-5556 install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r -e saveProof true app.finance.android.test/androidx.test.runner.AndroidJUnitRunner
shasum -a 256 androidApp/build/outputs/apk/debug/androidApp-debug.apk
git diff --check
git diff --exit-code -- shared/schemas
git ls-files --others --exclude-standard -- shared/schemas
```

AVD: API 36 / Android 16, arm64, 8 GiB, read-only session, порт 5556.
Запуск: `emulator -avd Finance_API_36_8GB -port 5556 -read-only
-no-snapshot-save -no-window -no-audio` с проектным ANDROID_AVD_HOME.

## Промежуточные ошибки и ограничения

- Первый build без project GRADLE_USER_HOME ожидал сетевое разрешение зависимостей;
  остановлен, результат не засчитан. Повтор с готовым кешем и --offline успешен.
- RED: 1 UI-тест, 1 ожидаемый отказ на отсутствии видимого inline-сообщения.
  После изменения GREEN: 1/1, обе темы и обе причины ошибки.
- Первый сбор скриншотов после Gradle connected-теста получил
  `run-as: unknown package`: runner уже удалил APK. Это не PNG-доказательство.
  Поэтому полный suite запускается через adb с отдельной установкой APK.
- Shared allTests в базовом build были UP-TO-DATE; для нового результата
  выполнен отдельный --rerun-tasks, старые counts не выдаются за свежий запуск.
- Физический Pixel, API 29, полноценный TalkBack и real-model inference:
  NOT RUN, вне объёма этого пункта. Проверка Error semantics не заменяет TalkBack.
- Независимый статический review: actionable findings отсутствуют;
  reviewer не выполнял Gradle или device-проверки.

## Проверки host

| Проверка | Наблюдаемый результат |
|---|---|
| Portable JVM без SDK, --rerun-tasks | PASS: 188, 0 failures/errors/skips |
| Shared allTests, --rerun-tasks | PASS: 188 JVM + 92 debug + 92 release, 0 failures/errors/skips |
| App JVM | PASS: 50, 0 failures/errors/skips |
| Python | PASS: 12/12 |
| ktlint, Android lint, обе debug APK | PASS |
| Source/packaged-manifest boundaries, schema diff | PASS |

Повторные JVM-прогоны не суммируются в число уникальных сценариев.

Debug APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
SHA-256: `0fee05ed8cc8a06bb44e729f294632da466812e4ea543ec3f0c85561a23386cc`.
Сборка обычная, без native AI; результат не квалифицирует модель.

Локальные логи: `build/reports/backlog-001/` (core.log, android.log,
shared-fresh.log, python.log, boundaries.log, red-ui.log, green-ui.log, full-ui.log).
RED/GREEN XML сохранены в red-results/green-results.
Отчёты host: `shared/build/test-results/`, `androidApp/build/test-results/`,
`androidApp/build/reports/lint-results-debug.html`.
Большие артефакты не включаются в Git. CI workflow Validate сохраняет
android-validation с APK и тестовыми отчётами; локальный APK hash относится
именно к локальной сборке и не заявляет byte-identical CI build.

## Проверка устройства

Полный instrumentation: **95 PASS / 4 SKIP / 0 FAIL**, 99 выбранных тестов,
195.189 секунды. Это обычная сборка: три opt-in real-model сценария и один
native-library-only сценарий пропущены явно. Эти пропуски не относятся к
отображению ошибки и не объявляются успешной проверкой inference.
Новый регрессионный тест также прошёл в составе полного набора.

Скриншоты извлечены через `adb exec-out run-as app.finance.android cat files/<name>.png`,
проверены как PNG 1080×2400 и визуально просмотрены:
`artifacts/screenshots/backlog-001/backlog-001-false-0.png`,
`backlog-001-false-9.png`, `backlog-001-true-0.png`, `backlog-001-true-9.png`.
Сообщения и общий статус видимы в обеих темах. Примеры целиком синтетические.

## Интеграция

До успешных CI и подтверждённого merge этот пункт не завершён в main.
Следующий пункт автоматически не запускается.

Опубликованный commit реализации: `173207fef773c85efa84d800566f59eefc652c84`.
Его tree `e6c4c606bcde5927aa9803ddfb324bf5a4641e96` совпал с локально проверенным.
Публикация через GitHub connector: у локального Git отсутствует push credential;
локальный checkpoint сохранён, reset/force-push не применялись.
[Первый CI run](https://github.com/Vitazine/finance-app/actions/runs/41768181880).
После этого уточнения документации обязателен CI нового head; текущие runs и
артефакт android-validation доступны во вкладке Checks PR. Итоговый merge SHA
записывается GitHub в PR, поэтому не подменяется заранее известным head SHA.
