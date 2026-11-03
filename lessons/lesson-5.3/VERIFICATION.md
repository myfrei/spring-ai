# Проверка урока 5.3

Дата: 2026-11-02. Java Oracle 25.0.5.0.3+1-LTS-2, Maven Wrapper 3.9.11, Spring Boot 4.1.2, Spring AI 2.0.2, Docker 29.4.0, Git 2.50.1, Node 24.16.0. Ответы модели задаются локальной Ollama HTTP fixture; файловые операции и PostgreSQL настоящие.

## Матрица

| Срез | Java | pgvector IT | JavaScript | Stock Room |
| --- | --- | --- | --- | --- |
| lecture | 432 PASS | 39 PASS | 104 PASS | 4 PASS |
| homework, готовая база | 435 PASS | 39 PASS | 104 PASS | 4 PASS |
| Временный эталон homework | 443 PASS | 39 PASS | База без изменений | База без изменений |
| Новые приёмочные в каркасе | 8 TODO errors | Не применимо | Не применимо | Не применимо |

Полные clean verify закончились без failures/errors/skips. 432 включает 410 проверок базы 5.2 и 22 новых сценария controlled/workspace. Три предоставленных adapter-теста ДЗ проходят без заполнения TODO; восемь новых приёмочных проверяют только ProposalBatchPolicy. Эталон хранится вне курса и отличается от каркаса только реализацией policy.

## Повторение

Из lecture или homework:

```bash
./mvnw clean verify -Ppgvector-it
npm ci
npm test
./mvnw -f workspace-fixture/pom.xml test
```

Для pgvector IT нужен Docker. Новые файловые и HTTP-тесты не требуют настоящей модели. Отдельный профиль mcp-host-it и standalone MCP-проекты сохранены из прежних уроков; в этом срезе повторно проверены полная fast suite, pgvector и JS, а не прежний отдельный MCP child-process профиль.

В homework:

```bash
./mvnw -Phomework -Dtest=ProposalBatchPolicyAcceptanceTest test
```

До решения ожидаются восемь UnsupportedOperationException из одного TODO. После реализации:

```bash
./mvnw clean verify -Phomework,pgvector-it
```

## Что подтверждено

- ASK запрещает предложение, PLAN создаёт только PREVIEW, EDIT создаёт PENDING. Модель не получает approval-tool.
- Проверены same-run receipts, текущий hash, полное покрытие изменяемых строк, неоднозначное вхождение, LF/CRLF/CR и fragment с завершающим разделителем.
- Проверены TTL, активный run, reject, replay, два конкурентных APPROVE, два предложения с одной исходной версией, symlinks и очистка временного файла.
- Новые HTTP-тесты используют настоящий Boot и OllamaChatModel: readFile возвращает реальные строки/hash, затем модельная fixture запрашивает proposePatch; решение отправляется отдельным запросом.
- Независимо запущен собранный executable JAR: EDIT дал APPLIED после трёх model calls; повторное решение — HTTP 409. REJECT сохранил исходные байты; внешняя правка перед APPROVE дала STALE_PATCH и сохранилась на диске. PLAN остался PREVIEW, ASK не создал proposal. Добавление baseHash в decision body отклонено HTTP 400.
- Все 353 файла лекционной базы присутствуют в homework. Допустимые различия — имя приложения/артефакта и README; код и тесты базовой функции сохранены.

## Граница результата

APPLIED подтверждает замену одного файла; проверку кода агент ещё не запускает. Preview — учебное представление exact replacement, не вход для git apply. Atomic move не обеспечивает compare-and-swap против стороннего процесса. API рассчитано на localhost и одного пользователя, approvals и runs находятся в RAM. Настоящие веса Ollama и семантическое качество ответа не проверялись; бланк ручного эксперимента оставлен пустым.
