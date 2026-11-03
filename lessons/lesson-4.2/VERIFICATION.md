# Проверка урока4.2

Дата: **2026-10-31**. В каждом lecture/homework есть **два независимых Maven-проекта**: корневой RepoPilot и task-server. Корневой POM не является aggregator.

## Lecture: приложение RepoPilot

Полная база4.1 перенесена в4.2 с изменением artifact/app/probe metadata; локальные tools не переключаются на MCP автоматически.

```bash
cd lecture
./mvnw verify -Ppgvector-it
npm ci
npm test
```

**310 Java/HTTP +35 pgvector IT +104 JS** зелёные, без пропусков. Для PostgreSQL профиля нужен Docker; новый STDIO-сервер его не использует.

## Lecture: настоящий SDK-сервер

```bash
./mvnw -f task-server/pom.xml verify
java -jar task-server/target/taskshelf-mcp-server.jar
```

Результат: **9 unit +8 STDIO/SDK IT**, failures/errors/skips=0. SDK **2.1.0** закреплён явно; effective mcp-core и mcp-json-jackson3 тоже2.1.0. Сам импорт Spring AI BOM2.0.2 не управляет прямой координатой mcp. Boot parent используется только для сборки/dependency management; сервер не запускает Spring и не требует модели.

Maven shade собирает обычный исполняемый JAR. Native SDK client и отдельный raw JSON-RPC клиент реально запускают этот JAR дочерним Java-процессом:

- initialize/initialized: negotiated protocolVersion **2025-11-25**. Дополнительно проверен совместимый older2025-06-18.
- capabilities: static tools/resources/prompts и logging:{}; последнее SDK2.1.0 добавляет сам. Protocol logging notifications приложение не отправляет; обычные логи пишутся в stderr.
- tools/list, listTasks(limit), getTask, resources/list/read, prompts/list/get возвращают заявленные schema/данные. Internal acceptanceCriteria в исходный getTask не включены.
- Integer/fraction/string/null/unknown fields, неизвестный task и unknown protocol operation различаются: business failure — CallToolResult.isError, protocol error — JSON-RPC error envelope. Точные codes проверяются в tests.
- Каждый stdout frame — одна JSON-RPC строка; многострочный text внутри JSON экранирован. Stderr содержит локальную readiness запись.
- EOF stdin завершает server с code0. SDK client close может завершить subprocess через SIGTERM/code143; процесс в обоих случаях исчезает. Все тестовые процессы остановлены.

Отдельно **Inspector0.21.2** выполнил семь CLI-операций с семантической проверкой JSON: tools/list, getTask, listTasks(limit2), resources/list/read, prompts/list/get. Это реальный protocol test без участия LLM, а не mock Inspector и не только проверка exit code. Команды закреплены в [лекции](lecture/README.md).

TaskServerJson ограничивает encode(value) до8192 UTF-8 bytes и возвращает его как TextContent плюс structuredContent. Полная JSON-RPC строка больше из-за двух представлений, escaping и envelope. Resource text ограничен отдельно; inputMaxSize STDIO — отдельная настройка SDK. Ни один из этих лимитов не является model-token budget.

## Homework: отдельный checklist

Корневое приложение homework прошло **310 Java +35 pgvector IT +104 JS**. Новый TODO расположен только в task-server/checklist и не меняет базовый app.

```bash
cd homework
./mvnw -f task-server/pom.xml -DskipTests package
./mvnw -f task-server/pom.xml '-Dtest=*Test,!AcceptanceChecklistAcceptanceTest' verify
./mvnw -f task-server/pom.xml -Dtest=AcceptanceChecklistAcceptanceTest test
```

Каркас server компилируется; база **12 unit +8 IT** зелёная (9 lecture +3 provided adapter checks). Новые **8 acceptance красные**: восемь TODO errors. Временный эталон с заменой только AcceptanceChecklistPolicy.java прошёл **20 unit +8 IT**, без изменения тестов. Эталон не включён в homework. После независимого review восемь acceptance усилены: tight budget после NFKC/whitespace (включая expansion лигатуры), приоритет maxItems перед проверкой следующего budget и подсчёт blank/duplicate в полном eligible наборе. Целевой повтор11 policy/adapter-проверок зелёный; implementation не менялась.

Новый ChecklistServerMain запускается через java -cp и композиционно добавляет третий tool. Исходный java -jar entrypoint остаётся двухинструментным; транспорт/resources/prompts повторно не реализуются.

Inspector0.21.2 проверил **10 операций** нового reference-entrypoint: три tools, полный checklist (3 criteria/146 code points), partial1/omitted2, budget40/blocked, unknown task, invalid fraction, resource и prompt. Semantic JSON assertions прошли. Важная деталь клиента: Inspector по integer schema может преобразовать строковый CLI argument в число до отправки. Поэтому серверная строгость проверена отдельно raw STDIO: string integer, fraction, null и unknown key отвергаются; валидный запрос после них успешен, EOFcode0, input marker не отражается в ошибке. Все дочерние процессы остановлены.

## Среда и границы

Temurin25.0.5+8, Wrapper3.9.11, Node24.16.0/npm11.13.0, MCP SDK2.1.0, Inspector0.21.2; приложение сохраняет Spring Boot4.1.2/Spring AI2.0.2. Standalone STDIO не открывает HTTP-порт и не вызывает Ollama, PostgreSQL или Docker.

Каталог — фиксированные вымышленные данные TaskShelf. Проверка MCP с настоящим Java-процессом не доказывает, что произвольная модель умеет выбирать tools: host/model integration появится отдельно. Prompt template не вызывает LLM и не выполняет содержащуюся в нём задачу. Read-only annotations — protocol hints; фактическая read-only граница обеспечивается кодом каталога.

Курс фиксирует совместимую ревизию2025-11-25; generic текущая Inspector документация может показывать2026-07-28. Новые transport/lifecycle возможности не подставлялись в этот SDK-срез. Streamable HTTP/Spring MCP — следующий урок4.3.
