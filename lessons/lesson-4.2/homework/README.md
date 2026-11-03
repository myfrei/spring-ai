# ДЗ4.2. Целые критерии приёмки через MCP

[Материалы урока](../README.md) · [Лекция](../lecture/README.md) · [Приёмочные тесты](task-server/src/test/java/dev/myfrei/repopilot/checklist/AcceptanceChecklistAcceptanceTest.java) · [Отчёт по ДЗ](SUBMISSION.md) · [Проверки примера](../VERIFICATION.md)

Вся база лекции готова: приложение RepoPilot и отдельный task-server на Java MCP SDK, tools/listTasks/getTask, resource, prompt и STDIO lifecycle. Добавьте `getAcceptanceChecklist`: выдавайте **целые** критерии приёмки с ограничением количества и Unicode-объёма. Используйте готовый сервер и getTask; реализовать предстоит новую политику формирования checklist.

Единственный TODO: [AcceptanceChecklistPolicy.build](task-server/src/main/java/dev/myfrei/repopilot/checklist/AcceptanceChecklistPolicy.java). Весь MCP adapter, schema, валидация, entrypoint и codec предоставлены.

## Два проекта и два entrypoint сервера

`homework/pom.xml` — полное приложение RepoPilot из4.1. `homework/task-server/pom.xml` — независимый SDK-сервер, без LLM/SQL зависимостей. Корневой Maven не является aggregator: сервер проверяется отдельной командой.

Обычный `java -jar task-server/target/taskshelf-mcp-server.jar` по-прежнему запускает два исходных tools. Для ДЗ предусмотрен **отдельный** main:

```bash
"$JAVA_HOME/bin/java" -cp "$PWD/task-server/target/taskshelf-mcp-server.jar" \
  dev.myfrei.repopilot.checklist.ChecklistServerMain
```

Он переиспользует `TaskServerMain.start(catalog,extraTools)` и добавляет третий tool. Поэтому незаполненный новый TODO не нарушает исходные tools и baseline-контракты. Не запускайте Maven вместо java как STDIO-команду Inspector: сообщения сборки испортят protocol stdout.

## Что делает предоставленный tool

`getAcceptanceChecklist` получает JSON arguments:

```json
{"taskId":"TASK-101","maxItems":4,"maxCodePoints":400}
```

- taskId обязателен, формат TASK-[0-9]{3}; неизвестная задача даёт tool result isError=true/TASK_NOT_FOUND.
- maxItems необязателен, integer1..8, default4.
- maxCodePoints необязателен, integer40..800, default400.
- Fraction/string/null вместо integer и неизвестные поля отвергаются: isError=true/INVALID_ARGUMENTS.
- Raw criteria клиент не передаёт. Adapter читает внутреннюю Task.acceptanceCriteria по taskId; публичный getTask их не возвращал.
- До решения TODO новый tool возвращает isError=true/HOMEWORK_NOT_IMPLEMENTED. Это ожидаемое состояние каркаса, не успешная выдача checklist.

Read-only annotations являются подсказками протокола. Фактическое отсутствие записи обеспечено кодом и фиксированным каталогом. Шаблон prompt не запускает модель, чтение criteria также не вызывает LLM.

## Вход и выход policy

Готовый `ChecklistInput(taskId,criteria,maxItems,maxCodePoints)` проверяет границы: не более20 исходных строк, каждая не длиннее4000 Java chars, null запрещён; blank строки допустимы для нормализации. Контейнер копируется.

Верните immutable `AcceptanceChecklist`:

```text
taskId
items: [{id, text}]
eligibleCount
omittedCount
usedCodePoints
complete
budgetBlocked
```

Алгоритм нового приращения:

1. Для каждой исходной строки применить NFKC, заменить последовательности Unicode whitespace одним обычным пробелом и убрать пробелы с краёв.
2. Пропустить пустые строки. Удалить **точные** дубликаты нормализованного текста, сохраняя первое появление. Case folding не применяется.
3. ID критерия основан на его **исходной позиции**, начиная с1: AC-001, AC-002… Пропуски и дубликаты не перенумеровывают последующие записи. Эти IDs устойчивы для данного исходного списка, не обещают сохранение при его редактировании.
4. eligibleCount — количество непустых уникальных нормализованных критериев во всём входе.
5. Выбирать prefix этого списка. Сначала ограничить maxItems. Затем каждый очередной **целый текст** должен помещаться в оставшийся budget по `String.codePointCount`, а не UTF-16 length.
6. Нельзя обрезать критерий или пропускать слишком большой ради короткого следующего. Если очередной не помещается, остановиться и вернуть budgetBlocked=true. Если раньше остановил maxItems, budgetBlocked=false.
7. usedCodePoints — сумма code points только выбранных нормализованных текстов. IDs, JSON и separator в этот счётчик не входят: это лимит содержимого, не bytes/network/model-token budget. Codec сервера отдельно ограничивает JSON payload значения tool8192 UTF-8 bytes. Полная JSON-RPC строка больше: она содержит TextContent и structuredContent плюс envelope/escaping; это отдельные уровни.
8. omittedCount=eligibleCount−items.size; complete=true только при omittedCount=0. Пустой eligible список — complete=true, usedCodePoints=0, budgetBlocked=false.

Пример: исходные строки `[" ","  ＡＰＩ работает ","API работает","Тест проходит"]`. После обработки остаются AC-002 и AC-004. Если первый целый критерий не помещается, items пуст и complete=false. Частичный текст не превращается в якобы полный критерий.

## Проверки

Из homework:

```bash
./mvnw test
./mvnw -f task-server/pom.xml -DskipTests package
./mvnw -f task-server/pom.xml '-Dtest=*Test,!AcceptanceChecklistAcceptanceTest' verify
./mvnw -f task-server/pom.xml -Dtest=AcceptanceChecklistAcceptanceTest test
```

Восемь новых acceptance сначала красные из-за одного TODO. Готовые protocol adapters и исходные MCP-контракты должны проходить. После реализации:

```bash
./mvnw -f task-server/pom.xml verify
./mvnw verify -Ppgvector-it
npm ci
npm test
```

Первый verify собирает shaded JAR и проверяет настоящий отдельный STDIO-процесс SDK-клиентом. Второй относится к унаследованному RAG приложения и требует Docker. Новая policy не использует Docker/Ollama/PostgreSQL. [Результаты проверки примера](../VERIFICATION.md).

## Inspector 0.21.2 и отдельный entrypoint ДЗ

Из `homework` используйте **закреплённую версию 0.21.2**. Запускайте CLI-команды с Java 25; `JAVA_HOME` должен указывать на ваш JDK 25. Inspector сам запускает и завершает дочерний сервер, поэтому отдельно открытый `java -cp` процесс для этих команд не нужен.

```bash
npm exec --yes --package=@modelcontextprotocol/inspector@0.21.2 -- \
  mcp-inspector --cli "$JAVA_HOME/bin/java" \
  -cp "$PWD/task-server/target/taskshelf-mcp-server.jar" \
  dev.myfrei.repopilot.checklist.ChecklistServerMain \
  --method tools/list
```

Ожидаются ровно `listTasks`, `getTask`, `getAcceptanceChecklist`. Если видны только два исходных tool, вы выбрали основной manifest entrypoint `java -jar` вместо `ChecklistServerMain`.

Вызов с полным допустимым budget:

```bash
npm exec --yes --package=@modelcontextprotocol/inspector@0.21.2 -- \
  mcp-inspector --cli "$JAVA_HOME/bin/java" \
  -cp "$PWD/task-server/target/taskshelf-mcp-server.jar" \
  dev.myfrei.repopilot.checklist.ChecklistServerMain \
  --method tools/call --tool-name getAcceptanceChecklist \
  --tool-arg taskId=TASK-101
```

В исходном каркасе это `isError=true/HOMEWORK_NOT_IMPLEMENTED`. После реализации policy для текущей TASK-101 ожидаются три целых критерия, `complete=true`, `omittedCount=0` и `usedCodePoints=146`.

Проверка ограниченного prefix:

```bash
npm exec --yes --package=@modelcontextprotocol/inspector@0.21.2 -- \
  mcp-inspector --cli "$JAVA_HOME/bin/java" \
  -cp "$PWD/task-server/target/taskshelf-mcp-server.jar" \
  dev.myfrei.repopilot.checklist.ChecklistServerMain \
  --method tools/call --tool-name getAcceptanceChecklist \
  --tool-arg taskId=TASK-101 --tool-arg maxItems=1
```

После реализации остаётся AC-001: `complete=false`, `omittedCount=2`, `usedCodePoints=45`, `budgetBlocked=false`. Для проверки объёма замените `maxItems=1` на `maxCodePoints=40`: первый целый критерий не помещается, поэтому items пуст, `omittedCount=3` и `budgetBlocked=true`.

Для негативных сценариев замените taskId на TASK-999 либо передайте `--tool-arg maxItems=1.5`: ожидаются `TASK_NOT_FOUND` и `INVALID_ARGUMENTS`. Проверяйте поле `isError` и JSON-содержимое, а не только exit code Inspector: корректно доставленная tool error также может завершить CLI с кодом 0.

Inspector 0.21.2 преобразует числовые строки по integer schema перед отправкой. Поэтому его CLI не доказывает, что сервер получил raw JSON string. Строгие raw-типы проверяются отдельно adapter/SDK-тестами; дробный аргумент 1.5 сохраняется и подходит для CLI-негатива. После первого скачивания добавьте флаг `--offline` к тем же `npm exec` командам; сами проверки сервера не требуют сети.

Не вводите команды вручную в работающий protocol stdout. Остановку и stderr проверяйте как в лекции. Выдача неполного списка — успешное чтение с complete=false, а не isError=true. Невалидный запрос, неизвестный ID и незаполненный TODO — tool errors с отдельными code.

## Приёмка

Восемь policy-тестов проверяют целые строки/счётчики, Unicode whitespace/NFKC/dedup/IDs, maxItems, oversized-first, запрет skip, code points и точную границу, пустой результат/case, immutable snapshot и input bounds. Три adapter-теста проверяют schema, invalid args до policy и передачу внутренних criteria.

Заполните [SUBMISSION.md](SUBMISSION.md), приложите protocol результаты и объясните разницу между limits. Сохраните тесты без изменений и явно отличайте partial checklist от полного.
