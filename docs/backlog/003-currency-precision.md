# Backlog №3 — точность выбранной валюты

Дата: 2026-11-08. Статус: реализовано и локально проверено. До успешных CI
и merge не завершено в main; включение этой записи в main через PR завершает пункт.

Спецификация: пункт №3 docs/IMPROVEMENT_BACKLOG.md. Только подсказка в редакторе
ручной операции. Источник — CurrencyCode.resolve, как в buildEntry: trim/uppercase,
существующий frozen ISO exponent. Денежные правила и парсер не меняются.

Решение: обновлять supportingText суммы при изменении валюты. Для exponent=0
подсказать целую сумму; для остальных показать число дробных знаков и разделитель.
Для неполного/неизвестного кода оставить общую подсказку. Ошибка суммы имеет
приоритет, после исправления возвращается подсказка. Текст ввода не округляется
и не переписывается. Другие экраны и следующие пункты вне области.

План (writing-plans, исполнение в текущей сессии):
- [x] LedgerUiTest: UI regression с USD/JPY/KWD/CLF, lowercase+spaces,
      неполным/неизвестным/undefined кодом; сохранность ввода, error priority,
      обе темы и fontScale=2, отсутствие записей до Save и exact minor после Save.
- [x] Запустить новый тест на исходном коде и зафиксировать RED.
- [x] EntryEditor.kt + ledger_strings.xml: минимальная динамическая подсказка.
- [x] GREEN, formatter, полный host suite/build/lint/privacy/schema, полный UI.
- [x] Синтетические screenshots и независимый review финального diff.
- [ ] Отчёт, backlog, commit, PR, успешный CI финального head, merge и проверка main.

Рабочая ветка codex/backlog-003-currency-precision, исходное дерево чистое,
base fb126710fac7facc067d42759132cc674035666d. Продолжается согласованный цикл
одного пункта. Реализация здесь, review отдельным агентом по executing-plans.


## Первые результаты

Новый UI-тест: RED 1/1 на отсутствующей подсказке (LedgerUiTest.kt:431),
после реализации GREEN 1/1, 52s build+test. Проверены USD/JPY/KWD/CLF,
lowercase с пробелами, пустой/неполный/неизвестный/undefined/слишком длинный код,
обе темы/fontScale=2, error priority, возврат hint, неизменность суммы,
0 записей до Save и точные 150 minor после исправления. Исходные XML сохранены
в build/reports/backlog-003/red-results и green-results.

Read-only review: actionable findings отсутствуют; git diff --check PASS.
Python 12/12 PASS; source boundaries PASS. Итоговые проверки ниже.
Первый запуск host-suite не исполнился из-за timeout auto-review permissions;
разрешённый повтор core запущен отдельно. Это NOT RUN первой команды, не FAIL тестов.


## Команды и host-результаты

Локальные Temurin17.0.21+9, SDK36, Gradle cache проекта. Все Gradle-команды
с --offline --no-daemon; полный core и Android baseline с --rerun-tasks.
ANDROID_SERIAL=emulator-5556, AVD Finance_API_36_8GB API36 arm64 8GiB,
-read-only -no-snapshot-save -no-window -no-audio; отдельные случайные Room-базы.

```sh
git fetch origin
git switch -c codex/backlog-003-currency-precision origin/main
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.finance.android.LedgerUiTest#currencyPrecisionHintUpdatesWithoutChangingAmountInBothThemes
./gradlew :androidApp:ktlintFormat :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.finance.android.LedgerUiTest#currencyPrecisionHintUpdatesWithoutChangingAmountInBothThemes
./gradlew -PcoreOnly=true :shared:jvmTest :shared:ktlintCheck --rerun-tasks
./gradlew :shared:allTests :shared:ktlintCheck :androidApp:ktlintCheck :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --rerun-tasks
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check_boundaries.py
python3 scripts/check_boundaries.py --manifest androidApp/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml
adb -s emulator-5556 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s emulator-5556 install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r -e saveProof true app.finance.android.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
git diff --exit-code -- shared/schemas
git ls-files --others --exclude-standard -- shared/schemas
shasum -a 256 androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Core без ANDROID_HOME/ANDROID_SDK_ROOT: 188 PASS,17 tasks executed,36s.
Android baseline: 188 JVM+92 debug+92 release shared,50 app unit;176 tasks
executed,1m50s. Во всех XML 0 failures/errors/skips. Повторный JVM не суммируется
как уникальный тест. Python12 PASS. Ktlint/lint/build/source и packaged-manifest
privacy/schema/diff PASS. Зависимости, schema и финансовые правила неизменны.

Обычный APK без native AI: androidApp/build/outputs/apk/debug/androidApp-debug.apk.
SHA256: 4cae18731f60c870d04a000a5b2e174043a8532c6e41b84b2c6d92cbff50fadc.
Логи: build/reports/backlog-003/{red-ui,green-ui,core,android,python,boundaries,
packaged-boundaries,full-ui}.log. Большие артефакты не публикуются в Git.
NOT RUN: физический Pixel, API29, полноценный TalkBack, real-model inference.
Изменение не заявляет готовность AI или release qualification.


## Финальный UI и интеграция

Полный instrumentation на финальном APK: **99 PASS / 4 SKIP / 0 FAIL**,
103 selected,206.413s. Три opt-in real-model сценария и один native-library-only
пропущены явно; эти пропуски не являются успешной квалификацией модели.
Новый тест прошёл и в полном наборе. Ошибок после реализации не обнаружено.

PNG1080×2400 извлечены через adb exec-out run-as и визуально просмотрены:
artifacts/screenshots/backlog-003/backlog-003-false.png и backlog-003-true.png.
Синтетические данные; подсказка KWD, сумма и кнопки читаемы в обеих темах
при fontScale=2. Точный ввод 1,2345 сохранён, лишний знак не округляется.

Diff ограничен EntryEditor, строковыми ресурсами, одним UI-тестом и документацией.
Нет новых зависимостей, сетевых вызовов, персональных данных или секретов.
Review без actionable findings; финансовая валидация и приоритет ошибки сохранены.

PR проходит существующий Validate core/android на финальном head. CI компилирует
instrumentation APK, а device execution подтверждён локально выше. GitHub connector
публикует в существующий origin Vitazine/finance-app; tree должен совпасть с локальным
проверенным коммитом. Сохраняется checkpoint, без reset/force-push. Merge только после
успешного CI с expected head SHA. Фактические SHA и CI доступны в PR, включившем
отчёт в main. Android-validation содержит CI APK/reports; local hash не заявляет
byte-identical CI build. Следующий пункт не запускается автоматически.
