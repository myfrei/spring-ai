# Проверка урока 4.4

Дата: 2026-10-31. Java25.0.5+8, Maven Wrapper3.9.11, Boot4.1.2, SpringAI2.0.2, MCP SDK2.1.0, protocol2025-11-25, Node24.16.0/npm11.13.0. PostgreSQL — настоящий Docker-контейнер pgvector/pgvector:0.8.1-pg17. MCP-сервер в составном тесте — executable Spring Boot JAR из4.3.

## Матрица

| Срез | Проверки | Результат |
| --- | --- | --- |
| lecture, основной RepoPilot | 339 Java +39 pgvector IT +104 JavaScript | PASS |
| homework, готовая база | 342 Java +39 pgvector IT +104 JavaScript | PASS |
| Новые CircuitGate acceptance в исходном homework | 8 ERROR из-за двух TODO | Ожидаемый RED |
| Временный эталон HW, -Phomework | 350 Java +39 pgvector IT | PASS |
| lecture, optional mcp-host-it | 4 IT с настоящим упакованным MCP-сервером | PASS |
| Эталон HW, optional mcp-host-it | Те же4 IT | PASS |
| Эталон HW, circuit-it | 1 настоящий stop/restart/reconnect IT | PASS |
| Базовые HTTP/STDIO-проекты | Исходники совпадают с проверенным lecture4.3: HTTP10+9, STDIO9+8 | Проверено совпадение; результаты в4.3 |

339 Java включают старые310 и29 новых проверок: полный CallToolResult, native SDK/wire faults, движок/настоящий ToolCallingAdvisor, HTTP/Ollama body и production configuration/startup. В342 добавлены три готовых теста CircuitTaskGateway. Только восемь приёмочных тестов нового CircuitGate выключены в default; профиль homework включает их.

Временный эталон расположен вне курса. Он отличается от сдаваемого homework только реализацией `CircuitGate.java`; тесты/adapter/POM одинаковы. Два TODO в каркасе сохранены, исходные функции лекции не вырезаны. Проверено совпадение245 базовых source-файлов в4.3 и270 в4.4 между lecture/homework, с нормализацией только application name. Новые policy/adapter/tests добавлены отдельно.

## Воспроизведение

Из `lecture`:

```bash
./mvnw verify -Ppgvector-it
npm ci
npm test
./mvnw -f task-http-server/pom.xml -DskipTests package
./mvnw verify -Pmcp-host-it \
  -Dtaskshelf.http.jar="$PWD/task-http-server/target/taskshelf-mcp-http-server-0.0.1-SNAPSHOT.jar"
```

`pgvector-it` и `mcp-host-it` запускайте отдельно: каждый задаёт свой список Failsafe-тестов. Оба требуют Docker. mcp-host-it дополнительно требует заранее собранный JAR по абсолютному пути; Maven внутри теста не запускается. Тестовый сервер получает свободный порт, а не занимает8092. Приложение обращается к HTTP-фикстуре Ollama; реальные модели для этих проверок не скачиваются.

В homework сначала подтвердите базу, затем новый контракт:

```bash
./mvnw test
./mvnw -Phomework -Dtest=CircuitGateAcceptanceTest test
```

Вторая команда ожидаемо красная до реализации. После решения:

```bash
./mvnw verify -Phomework,pgvector-it
./mvnw -f task-http-server/pom.xml -DskipTests package
./mvnw verify -Phomework,mcp-host-it \
  -Dtaskshelf.http.jar="$PWD/task-http-server/target/taskshelf-mcp-http-server-0.0.1-SNAPSHOT.jar"
./mvnw verify -Phomework,circuit-it \
  -Dtaskshelf.http.jar="$PWD/task-http-server/target/taskshelf-mcp-http-server-0.0.1-SNAPSHOT.jar"
```

В каждом запуске выбирайте один IT-профиль: pgvector-it, mcp-host-it либо circuit-it. Профиль homework меняет только выбор acceptance и сочетается с каждым. circuit-it не требует Docker/LLM: запускается настоящий HTTP-сервер и SDK-клиент, время gate управляется Clock. Детали приёмки — в [homework README](homework/README.md).

## Что подтверждено

- Настоящее production-приложение стартует с включённым host/RAG при MCP, PostgreSQL и Ollama на недоступном127.0.0.1:1: нет eager MCP connection. При выключенном host или RAG контроллер не регистрируется. Проверка не подставляет тестовый McpHostEngine вместо production configuration.
- При global output reserve64 host сохраняет собственный reserve256 и safety>=256, соответствующие финальному numPredict256. Selection использует отдельный output384, temperature0 и окно4096; final —256, temperature0.2 и то же окно, без доступных tools.
- Discovery не импортирует весь server catalog. Выбирается точный read-only getTask, host задаёт собственное имя taskshelf_getTask/description и закрытый scope аргументов. Collisions/несовместимая schema и запрос другого taskId отклоняются.
- Native SDK делает один callTool и проверяет полный CallToolResult: isError, public TaskView, точный requested id, типы/размеры, одинаковые content и structuredContent. Бизнес-ошибка, лишние поля, чужой ID, malformed/oversized результат не доходят до retrieval/final inference.
- WORKFLOW вызывает remote gateway в Java. MODEL использует реальный ToolCallingAdvisor и returnDirect, после чего выполняется отдельный RAG и финальный ChatClient без callbacks. Retrieval не повторяется внутри tool loop.
- 39 pgvector IT включают прежние35 и4 host-сценария с настоящими SQL/векторами, native MCP client и контролируемым HTTP peer. Финальная генерация и query embedding считаются отдельно; при NO_EVIDENCE final inference отсутствует.
- Четыре optional IT запускают настоящий Spring MCP JAR. Три проверяют native gateway/session reuse/Bearer. Четвёртый проводит задачу через HTTP-сервер4.3 → native discovery/callTool → настоящий pgvector → два изолированных model HTTP-запроса → REST-ответ host с источниками. Счётчики initialize/discovery/callTool/query embedding/selection/final согласованы с наблюдаемыми запросами.
- Digest проверяется до/после каждого model stage, включая provider error, и между stages; ошибки не запускают скрытую генерацию или fallback. Nullable provider usage сохраняет неизвестность, отчёт не включает raw question/TaskView summary/tool payload.
- При session404 SDK2.1.0 пытается автоматически инициализироваться. Публичный outbound customizer блокирует незапрошенный initialize до HTTP. Wire test подтверждает: initialize1 → failed tool call → всё ещё initialize1; только новый пользовательский запрос создаёт initialize2 и новую discovery. Это не наблюдатель результатов и не повторный callTool.

## Новое ДЗ и восстановление

Восемь acceptance проверяют consecutive failures/reset, threshold, точную границу cooldown, успешную/неудачную probe, одноразовые permits и generation, поздний failure после восстановления, конкурентные16 callers с одной probe, изоляцию экземпляров и clock rollback. Provided adapter проверяет все gateway kinds: только AVAILABILITY увеличивает outage, остальные ошибки сохраняются и не выдаются за сетевую аварию. Denied permit не достигает delegate; автоматических retries нет.

Настоящий CircuitGatewayIT выполнил warm read, остановил свой MCP-процесс, получил три AVAILABILITY, подтвердил OPEN и отсутствие delegate/SDK-вызова на следующем запросе, перезапустил сервер, проверил отказ до срока и одну probe на границе. После неё native gateway создал свежую session/discovery, вернул TASK-101 и gate стал CLOSED. Это проверка доступности MCP; состояние RAG/семантика ответа оцениваются отдельно.

## Границы доказательства

Сервер, transport, SDK, ChatClient/advisors и PostgreSQL настоящие. Ollama chat/embedding ответы — контролируемые HTTP-фикстуры, поэтому тесты доказывают контракты и оркестрацию, а не способность выбранных весов к tool calling или качество русского ответа. Для реальных весов заполните [бланк эксперимента](lecture/evaluation/Remote-Host-Experiment.md). Tool metadata и system prompt не гарантируют отсутствие семантических ошибок модели.

Circuit breaker локален одному процессу/сервису, без persistence, общего wall-clock deadline, scheduler и retries. MCP request timeout и LLM timeout действуют отдельно. Репозиторий не редактируется, shell-команды не исполняются; эти возможности относятся к следующему модулю.
