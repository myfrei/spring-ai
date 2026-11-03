# Проверка урока 5.6

Дата: 2026-11-02. Java Oracle 25.0.5.0.3+1-LTS-2, Maven Wrapper 3.9.11, Spring Boot 4.1.2, Spring AI 2.0.2, Docker 29.4.0, Node 24.16.0. Модельные ответы задаются HTTP fixtures; используются настоящие Boot, files и pgvector.

## Матрица

| Срез | Java | pgvector IT | JS | Stock Room |
| --- | --- | --- | --- | --- |
| lecture | 495 PASS | 39 PASS | 104 PASS | 4 PASS |
| homework, готовая база | 498 PASS | 39 PASS | 104 PASS | 4 PASS |
| Временный эталон homework | 506 PASS | 39 PASS | База без изменений | База без изменений |
| Новые приёмочные в каркасе | 8 TODO errors | Не применимо | Не применимо | Не применимо |

Clean verify прошли без failures/errors/skips. К базе 5.5 добавлено 17 проверок: 12 policy/projection и 5 настоящих Boot/Ollama HTTP. Три предоставленных adapter-теста ДЗ проходят без TODO; восемь приёмочных проверяют ToolRecallPolicy. Эталон вне курса отличается от homework только реализацией этой policy.

## Повторение

Из lecture или homework:

```bash
./mvnw clean verify -Ppgvector-it
npm ci
npm test
./mvnw -f workspace-fixture/pom.xml test
```

Для pgvector IT требуется Docker. Новые проверки используют HTTP fixture вместо настоящей модели. Код MCP-серверов и отдельный профиль mcp-host-it сохранены; повторный прогон прежних MCP child-process IT в этом срезе не выполнялся.

В homework:

```bash
./mvnw -Phomework -Dtest=ToolRecallPolicyAcceptanceTest test
```

До реализации ожидаются восемь UnsupportedOperationException из одного TODO. После решения:

```bash
./mvnw clean verify -Phomework,pgvector-it
```

## Подтверждённое поведение

- Projector сокращает только целые завершённые assistant/tool batches с полной корреляцией. Открытый или некорректный batch не превращается в допустимый контекст.
- Оригинальные вопросы, текстовые ответы, гипотезы, system state, Skill/reference exchanges и последний текущий exchange защищены. Прежний raw journal не меняется.
- Локальная оценка учитывает schemas и messages; итоговая проекция повторно проверяется Engine. Если protected minimum не помещается, нет fallback с удалением старого user text: происходит CONTEXT_LIMIT.
- Ни summary, ни metadata старого чтения не дают права записи и не завершают план. Состояние берётся из сервера; текущая версия файла не выводится из старого hash.
- Независимый executable JAR: четыре model calls / шесть tools завершили два read-шага. На последнем admission attempt оценка уменьшилась с 6848 до 4364 при лимите 5500; исключены два целых exchanges. Исходный журнал сохранил оба больших payload и все шесть коррелированных пар.
- Незавершённый план после фактического сжатия остался STOPPED / PLAN_INCOMPLETE. Бюджет 1 дал CONTEXT_LIMIT без model calls и освободил conversation.
- ДЗ возвращает только целый original readFile envelope того же run: проверяются whitelist, pairing, failed tool, stale/unavailable, повторы, UTF-8 payload budget и пропуск слишком большого результата. Вопросы, assistant prose и аргументы других tools не выдаются.
- В homework сохранены все 395 файлов лекционной базы; приватное решение не попало в материалы.

Дополнительный executable HW JAR proof подтвердил starter 501 и reference recall: точный original JSON/UTF-8 cost, DUPLICATE, TOOL_DENIED, FAILED_TOOL, OVER_BUDGET, STALE после изменения файла, UNAVAILABLE после удаления, NOT_FOUND для call другого run и 400 на поддельном currentHash. Не возвращаемые результаты оставались null.

## Граница результата

6848 и 4364 — оценки конкретного HTTP-сценария, а не измеренная экономия tokenizer или времени реальной модели. Provider usage учитывается отдельно. При отклонённой null-проекции актуальная оценка находится в последнем context.frames; общее поле run.report может хранить последнюю принятую оценку.

Это детерминированная проекция tool I/O, без вызова LLM-summarizer. При большом защищённом тексте runtime может остановиться. В committed window доступны четыре предыдущих turns; сам AgentStore ограничен и находится в RAM. Настоящие веса и качество ответа после сжатия не оценивались; бланк сравнения оставлен пустым.
