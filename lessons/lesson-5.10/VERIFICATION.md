# Проверка урока 5.10

Дата: 2026-11-03. Java 25.0.5, Maven Wrapper 3.9.11, Spring Boot 4.1.2, Spring AI 2.0.2, MCP Java SDK 2.1.0 / protocol 2025-11-25, Node 24.16.0, Docker 29.4.0.

## Лекционная база

| Проверка | Результат |
| --- | --- |
| Обычные Java-тесты основного приложения | 555, зелёные |
| pgvector integration | ещё 39, зелёные; PostgreSQL в Docker |
| `runner-it` | 6, зелёные; настоящий Docker runner |
| `capstone-it` | 1 сквозной сценарий, зелёный; настоящий отдельный MCP process, SDK и 4 Docker-проверки |
| Отдельный `capstone-task-server` | 2 unit + 2 integration, зелёные; настоящий SDK/HTTP |
| JavaScript | 112, зелёные |
| Правильный `workspace-fixture` | 4, зелёные |

`capstone-workspace` содержит намеренную ошибку равенства остатка. Его исходный провал — условие упражнения, а не ошибка сборки материалов. Проверка этой копии выполняется доверенным фиксированным `STOCKROOM_CONTRACT_V1`.

```bash
./mvnw test
./mvnw -Ppgvector-it verify
./mvnw -Prunner-it -Dverification.image="$VERIFIER_IMAGE_ID" verify
./mvnw -f capstone-task-server/pom.xml verify
./mvnw -Pcapstone-it -Dverification.image="$VERIFIER_IMAGE_ID" verify
npm ci
npm test
./mvnw -f workspace-fixture/pom.xml test
```

Integration-профили запускаются отдельно: их includes не следует объединять одним `-P` и считать, что выполнены все наборы. Команды сборки образа, фиксации image ID и запуска реальной модели приведены в [лекции](lecture/README.md).

## Сквозной integration test

`CapstoneWorkflowIT` использует отдельный executable Spring MCP server, Boot host и управляемую Ollama HTTP-фикстуру. Он проверяет полный положительный путь и отрицательные сценарии:

- настоящий SDK initialize → listTools → callTool; закреплённые задача, схема и protocol;
- чужой baseline FAIL отвергается;
- исходный Docker FAIL с exit 10;
- подготовка с явным Skill, собственным чтением, закрытием плана и одним proposal;
- повтор того же prepare request ID не вызывает модель снова;
- файл остаётся ошибочным до отдельного человеческого решения;
- настоящий PASS, полученный **до** применения patch, не подходит после применения;
- повторное approval не применяет patch второй раз;
- свежий Docker PASS с exit 0, затем отдельный read-only review;
- все 15 server gates обязательны для `DONE`;
- внешнее изменение файла переводит результат в `STALE`.

В сценарии 4 настоящих проверки: чужой FAIL, свой FAIL, слишком ранний PASS и свежий PASS. Вызовов HTTP-фикстуры модели — 6. Тесты MCP-модуля отдельно проверяют SDK discovery/call, закрытые аргументы, 401 без token и 403 при чужом Origin.

## Независимая проверка собранных JAR

Дополнительно запущены **два отдельных executable JAR**, а не только Spring test context: host RepoPilot и `capstone-task-server`. Использованы новая копия workspace, отдельные порты и тот же закреплённый image ID.

Получен реальный import receipt: protocol `2025-11-25`, один initialize, один listTools, один callTool. Новый отчёт имеет `INCOMPLETE`. Далее пройден путь:

```text
Docker FAIL / 10
→ prepare: readVerification + свой readFile + plan + Skill + proposal
→ отдельное ручное APPROVE patch
→ свежий Docker PASS / 0
→ отдельный review: readVerification + свой readFile + plan
→ DONE: 15 / 15 gates
→ внешняя правка файла
→ STALE
```

Итого 2 настоящих Docker-проверки и 6 HTTP-вызовов фикстуры Ollama. Повтор prepare не добавляет вызовов. Секрет MCP отсутствует в отчёте. Созданный proposal до approval не меняет файл. Все процессы проверки завершены.

Это доказательство связности runtime, версий, MCP и runner. Оно не оценивает качество принятия решений реальными локальными весами.

## Самостоятельное домашнее задание

ДЗ добавляет переносимый JSON-пакет для ревью. Оно не повторяет final workflow и не вычисляет готовность задачи заново.

| Проверка | Результат |
| --- | --- |
| Каркас, обычные Java-тесты | 558, зелёные: база + 3 теста адаптеров |
| Каркас, pgvector integration | ещё 39, зелёные |
| Каркас, JavaScript | 112, зелёные |
| Каркас, `workspace-fixture` | 4, зелёные |
| Новые приёмочные на TODO | 8 ожидаемо красных проверок на TODO |
| Закрытый контрольный вариант | 566 Java + 39 pgvector, зелёные |

Контрольная реализация находится вне материалов курса. В `homework` сохраняется весь лекционный код, включая отдельный MCP server и capstone fixture; единственный TODO — `PortableAuditPolicy`. Приёмочные проверяют canonical JSON, SHA-256 payload, allowlist, явные пропуски, частичный отчёт и бюджет полного UTF-8 документа. Новые тесты исключены из обычной зелёной базы и подключаются профилем `homework`.

```bash
./mvnw test
./mvnw -Phomework -Dtest=PortableAuditPolicyAcceptanceTest test
./mvnw -Phomework,pgvector-it verify
```

## Скачивание аудита через настоящие endpoints

Каркас и закрытый контрольный вариант отдельно запущены из executable JAR с профилем `capstone` и отдельными копиями workspace. Каждый получил задачу через настоящий MCP и прошёл реальный Docker FAIL → ручное применение patch → свежий PASS → review → DONE. Это ещё 4 Docker-проверки, 2 MCP imports и по 6 HTTP-вызовов фикстуры модели на приложение.

- Каркас возвращает HTTP 501 и на частичном, и на завершённом, и на устаревшем отчёте.
- Контрольный вариант отдаёт `INCOMPLETE` с тремя явными `null` evidence, затем честные `DONE` и `STALE` пакеты.
- Проверены точные canonical UTF-8 bytes, SHA-256 payload, соответствующий HTTP header и attachment. Повторный экспорт одинаковых фактов побайтово совпадает.
- В пакет не попадают diff, source content, raw logs, вопросы, абсолютные пути, token, URL и timestamps.
- Бюджет 1 байт даёт HTTP 409 без attachment; поддельный report в запросе — 400; неизвестная сессия — 404.
- Сам экспорт не вызывает модель и не меняет байты исходника.

Все процессы проверки завершены. Паритет подтверждён: все 503 исходных файла лекции сохранены в homework; закрытый контрольный вариант отличается только одной policy. Source-only материалы не содержат `target`, logs или контрольного решения.

## Границы результата

Фиксированный runner проверяет пять условий `StockLedger`, а не весь Maven/JUnit/REST-проект. Он не исполняет произвольный POM из исследуемого репозитория. Необязательный субагент не является условием `DONE`: доказательство собственного чтения родителем обязательно независимо от делегирования.

Сохранённый preview diff предназначен для ревью точной замены и не выдаётся за универсальный применимый Git patch. Состояние jobs, approvals и capstone хранится в памяти; продолжение после перезапуска не реализовано. Полного автоматического repair loop после неверной уже применённой правки здесь нет. Лимиты prepare/review и ручное восстановление описаны в лекции.

`SHA-256` экспортного пакета — контроль целостности, не подпись и не доказательство подлинности источника. `DONE` остаётся серверным состоянием конкретной версии. Реальную модель, длительность и оборудование нужно проверить пилотом; [бланк эксперимента](lecture/evaluation/Capstone-Experiment.md) оставлен пустым.
