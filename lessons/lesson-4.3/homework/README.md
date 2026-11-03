# ДЗ4.3. Готовность задачи по результатам проверок

[Материалы урока](../README.md) · [Лекция](../lecture/README.md) · [Приёмочные тесты](task-http-server/src/test/java/dev/myfrei/repopilot/readiness/ReleaseReadinessAcceptanceTest.java) · [Отчёт по ДЗ](SUBMISSION.md) · [Проверки примера](../VERIFICATION.md)

База лекции уже реализована: RepoPilot, отдельный STDIO-сервер и Spring MCP Streamable HTTP-сервер с tools/resource/prompt, Bearer и проверкой Origin. Добавьте детерминированную оценку готовности задачи по metadata проверок. Используйте готовый транспорт и базовые tools, чтобы сосредоточиться на новой политике оценки.

Единственный TODO — `ReleaseReadinessPolicy.assess` в `task-http-server/src/main/java/dev/myfrei/repopilot/readiness/`. Adapter, закрытая schema, fixture, DTO и валидация предоставлены. LLM не участвует в вынесении вердикта.

## Запуск готовой базы и нового tool

Из `homework`:

```bash
./mvnw test
./mvnw -f task-http-server/pom.xml verify
./mvnw -f task-server/pom.xml verify
```

Обычная проверка HTTP-модуля исключает восемь новых acceptance: базовые контракты и предоставленные adapter-тесты должны быть зелёными. Для целевого задания:

```bash
./mvnw -f task-http-server/pom.xml -Phomework test
```

До реализации восемь acceptance красные с `UnsupportedOperationException`. После реализации они должны проходить без изменения тестов.

Запустить HTTP-сервер с дополнительным tool:

```bash
./mvnw -f task-http-server/pom.xml -DskipTests package
export TASKSHELF_MCP_TOKEN=course-local-token
"$JAVA_HOME/bin/java" -jar task-http-server/target/taskshelf-mcp-http-server-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=release-homework
```

Профиль `release-homework` добавляет `getReleaseReadiness`; без профиля работают два исходных tool. Maven-профиль `homework` выбирает тесты, Spring-профиль `release-homework` выбирает runtime-компоненты — это разные механизмы. Сервер слушает `127.0.0.1:8092/mcp`, модель и Docker не нужны. Токен здесь учебный; он не заменяет production-аутентификацию.

В другом терминале из `homework`:

```bash
npm exec --yes --package=@modelcontextprotocol/inspector@0.21.2 -- \
  mcp-inspector --cli http://127.0.0.1:8092/mcp --transport http \
  --header "Authorization: Bearer course-local-token" --method tools/list

npm exec --yes --package=@modelcontextprotocol/inspector@0.21.2 -- \
  mcp-inspector --cli http://127.0.0.1:8092/mcp --transport http \
  --header "Authorization: Bearer course-local-token" --method tools/call \
  --tool-name getReleaseReadiness --tool-arg taskId=TASK-105
```

В каркасе новый tool возвращает `isError=true/HOMEWORK_NOT_IMPLEMENTED`. После решения для TASK-105 ожидается READY. Доставленная tool error может давать exit code0 у CLI: проверяйте `isError` и payload. После первого скачивания Inspector допускается `npm exec --offline`.

## Контракт

Клиент передаёт только `{"taskId":"TASK-105"}`. Отправить `events`, PASS или другой статус извне нельзя. Adapter получает статус задачи из `TaskCatalog` и server-owned учебные observations:

- текущая revision2, attempt1;
- три обязательных check: BUILD, UNIT, INTEGRATION;
- TASK-105: статус DONE и три PASS;
- остальные известные задачи: исходный статус каталога и три UNKNOWN.

Это **COURSE_FIXTURE**, а не сообщение о реально запущенных build/tests. Unit-тесты передают policy и другие комбинации наблюдений. Не подменяйте неизвестные результаты успешными и не запускайте команды из tool.

`ReadinessInput` содержит taskId, taskStatus, currentRevision и immutable events. Статусы задач: OPEN/IN_PROGRESS/DONE. Event: check, revision, attempt, status(PASS/FAIL/UNKNOWN). Границы уже проверяет DTO: taskId формата TASK-[0-9]{3}, revision и attempt1..100, до100 events.

Верните immutable `ReleaseReadiness`:

```text
taskId, revision, verdict
checks: [{name, state, attempt}]
ignoredStaleResults
reasons
origin: COURSE_FIXTURE
```

Правила:

1. Учитывайте только events с `revision == currentRevision`. Все остальные, включая более новую revision, входят в `ignoredStaleResults` и не подтверждают текущую готовность.
2. Для каждого check возьмите максимальный attempt среди текущей revision. Порядок прихода событий не влияет на результат.
3. Одинаковые статусы одного последнего attempt — безвредные дубликаты. Различные статусы этого attempt дают CONFLICT. Предыдущий PASS не заменяет более поздний UNKNOWN или конфликт.
4. Если check отсутствует, state=MISSING, attempt=0. Иначе state=PASS/FAIL/UNKNOWN/CONFLICT и фактический последний attempt. Порядок checks всегда BUILD, UNIT, INTEGRATION.
5. Вердикт BLOCKED, если задача не DONE **или** есть FAIL. Иначе UNKNOWN при MISSING, UNKNOWN или CONFLICT. Только DONE и три PASS дают READY. Конфликт PASS+FAIL одного attempt остаётся CONFLICT, а не доказанным FAIL; отдельный FAIL другого check всё равно блокирует.
6. Reasons добавляются в стабильном порядке: TASK_NOT_DONE при незавершённой задаче; FAILED_CHECK при FAIL; INCOMPLETE_CHECKS при MISSING/UNKNOWN; CONFLICTING_CHECKS при CONFLICT. Несколько причин могут сосуществовать.
7. Выходные списки неизменяемы; входные коллекции не менять. Сохраняйте taskId/revision и origin=COURSE_FIXTURE.

Пример: UNIT revision1/attempt9/PASS не подтверждает revision2. UNIT revision2/attempt1/PASS и attempt2/UNKNOWN дают UNKNOWN. UNIT revision2/attempt3/PASS плюс attempt3/FAIL дают CONFLICT независимо от порядка событий.

## Приёмка

Восемь acceptance проверяют READY, блокировку по status/FAIL, изоляцию revisions, последние attempts, дубликаты/конфликты, порядок событий, отсутствие свидетельств/immutability и сочетание причин. Три предоставленных adapter-теста проверяют schema, отказ до policy и происхождение fixture. Настоящий HTTP lifecycle остаётся в интеграционных тестах модуля.

После решения:

```bash
./mvnw -f task-http-server/pom.xml -Phomework verify
./mvnw -f task-server/pom.xml verify
./mvnw verify -Ppgvector-it
npm ci
npm test
```

Последняя Java-проверка относится к унаследованному RAG и требует Docker; само задание — нет. Заполните [SUBMISSION.md](SUBMISSION.md): приложите protocol-результаты READY, BLOCKED и один отказ по невалидному аргументу, а UNKNOWN покажите отдельным unit-сценарием policy. Текущий server fixture не содержит DONE-задачу с неполными проверками, поэтому UNKNOWN из него не ожидается. Не выдавайте fixture за реальные CI-результаты.
