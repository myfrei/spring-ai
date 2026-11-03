# Проверка урока 4.3

Дата: 2026-10-31. Проверка выполнена на macOS, Java25.0.5+8, Maven Wrapper3.9.11, Boot4.1.2, SpringAI2.0.2, MCP Java SDK2.1.0, Node24.16.0/npm11.13.0, Inspector0.21.2. Согласованная protocol revision —2025-11-25. Наличие более новой спецификации не подменяет проверенную Java-реализацию.

## Матрица

| Проект | Проверки | Результат |
| --- | --- | --- |
| lecture, основной RepoPilot | 310 Java +35 pgvector IT +104 JavaScript | PASS |
| homework, основной RepoPilot | 310 Java +35 pgvector IT +104 JavaScript | PASS |
| lecture/task-server | 9 unit +8 SDK/STDIO subprocess IT | PASS |
| homework/task-server | 9 unit +8 SDK/STDIO subprocess IT | PASS |
| lecture/task-http-server | 10 unit +9 настоящих HTTP/SDK IT | PASS |
| homework/task-http-server, default | 13 unit +9 HTTP/SDK IT | PASS |
| homework/task-http-server, -Phomework, исходный каркас | 13 готовых проверок PASS; 8 новых acceptance ERROR из-за TODO | Ожидаемый RED |
| Временный эталон HW, -Phomework | 21 unit +9 HTTP/SDK IT | PASS |
| Inspector HTTP, lecture | 7 операций с разбором JSON | PASS |
| Inspector HTTP, эталон HW | 7 операций +3 буквальных raw HTTP-негатива | PASS |

Эталон находится вне курса: в сдаваемом homework остаётся один незаполненный `ReleaseReadinessPolicy.assess`. Acceptance не отключены аннотацией и не изменялись ради зелёного результата. Maven-профиль homework включает их явно; обычная сборка проверяет предоставленную базу.

## Повторение

Из `lecture` или `homework`:

```bash
./mvnw verify -Ppgvector-it
npm ci
npm test
./mvnw -f task-server/pom.xml verify
./mvnw -f task-http-server/pom.xml verify
```

В homework до решения отдельно ожидается RED:

```bash
./mvnw -f task-http-server/pom.xml -Phomework test
```

После реализации той же командой должны пройти все21 unit; `verify` дополнительно проверит9 HTTP IT. Корневой проект и два server-проекта независимы: корневой Maven не собирает дочерние автоматически. PostgreSQL-проверкам нужен Docker; MCP server/unit/HTTP/STDIO tests не требуют Ollama и Docker. Для STDIO IT сначала автоматически собирается executable JAR на package-фазе.

## Что проверено через настоящий HTTP

- initialize/protocol/capabilities; два tools и закрытые input schemas; resource и prompt с required taskId;
- SDK getTask возвращает одинаковые публичные данные в TextContent и structuredContent, без acceptanceCriteria;
- listTasks integer bounds, fraction/string/null, неизвестные поля и неизвестный taskId; business errors остаются `CallToolResult.isError`, transport/JSON-RPC failures имеют другой уровень;
- bearer обязателен для MCP HTTP-методов, Origin проверяется раньше token; отсутствие Origin допустимо у native clients, запрещённый/множественный Origin отклоняется;
- session header не заменяет bearer; закрытие клиента/DELETE не останавливает сервер; работают независимые клиентские сессии;
- `/mcp;review=x` и encoded path variants не обходят auth/Origin; независимый повторный запуск подтвердил401 без bearer,403 с запрещённым Origin и200 initialize на допустимом запросе;
- буквальный raw JSON `limit:null` и неизвестное поле со значениемnull доходят до guards, а не исчезают в mapper.

В SpringAI2.0.2 обнаружены и учтены особенности интеграции: generated schema для raw CallToolRequest уточняется через публичные specifications; servlet customizer объединён с настройкой raw-валидации через @Primary; documented `mcpServerJsonMapper` сохраняет null map contents. Filter защищает все маршруты этого отдельного сервера. Это проверенные ограничения закреплённой версии, а не обещание совместимости с любым будущим starter.

Inspector lecture проверил tools/list, listTasks(limit2), getTask, TASK_NOT_FOUND, дробныйlimit, resource и prompt. Inspector эталона HW подтвердил три tools, READY дляTASK-105, BLOCKED дляTASK-101, TASK_NOT_FOUND/INVALID_ARGUMENTS и исходные resource/prompt. Дополнительные raw HTTP case передали taskId:null, taskId:number и events:null; они отвергнуты. Поле UNKNOWN из readiness policy проверяется unit-сценариями: текущий server fixture не содержит DONE-задачу с неполными checks.

## Границы доказательства

TaskCatalog и результаты readiness — учебные данные. Origin/Bearer — локальная демонстрационная граница, не production OAuth. Inspector доказывает доставку и контракт, а не качество ответа LLM. Сервер не запускает модель и не исполняет реальные CI-проверки; origin=COURSE_FIXTURE обязателен. Унаследованные LLM/embedding paths проверены HTTP-фикстурами, а pgvector — настоящей БД. Реальные установленные веса и качество русских ответов требуют отдельного прохождения бланка эксперимента.
