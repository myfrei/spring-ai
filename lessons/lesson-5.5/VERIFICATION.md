# Проверка урока 5.5

Дата: 2026-11-02. Java Oracle 25.0.5.0.3+1-LTS-2, Maven Wrapper 3.9.11, Spring Boot 4.1.2, Spring AI 2.0.2, Docker 29.4.0, Node 24.16.0. Файловая система и pgvector настоящие; ответы LLM задаются Ollama HTTP fixtures.

## Матрица

| Срез | Java | pgvector IT | JS | Stock Room |
| --- | --- | --- | --- | --- |
| lecture | 478 PASS | 39 PASS | 104 PASS | 4 PASS |
| homework, готовая база | 481 PASS | 39 PASS | 104 PASS | 4 PASS |
| Временный эталон homework | 489 PASS | 39 PASS | База без изменений | База без изменений |
| Новые приёмочные в каркасе | 8 TODO errors | Не применимо | Не применимо | Не применимо |

Clean verify завершились без failures/errors/skips. К базе 5.4 добавлено 25 проверок: 13 PlanSession, 5 Engine hooks и 7 Boot HTTP. Три предоставленных adapter-теста ДЗ зелёные сразу. Восемь новых приёмочных требуют только PlanFrontierPolicy. Эталон находится вне курса и отличается от homework только этой policy.

## Повторение

Из lecture или homework:

```bash
./mvnw clean verify -Ppgvector-it
npm ci
npm test
./mvnw -f workspace-fixture/pom.xml test
```

Для PostgreSQL IT нужен Docker. Новые plan-тесты не скачивают модель. Унаследованные MCP-серверы и профиль mcp-host-it сохранены; отдельные MCP child-process IT в этом срезе повторно не запускались.

В homework:

```bash
./mvnw -Phomework -Dtest=PlanFrontierPolicyAcceptanceTest test
```

До реализации ожидаются восемь UnsupportedOperationException из одного TODO. После решения:

```bash
./mvnw clean verify -Phomework,pgvector-it
```

## Подтверждённое поведение

- Ordered DAG, критерии READ_CODE/READ_TEST, обязательные шаги и пути принадлежат исходному запросу. Модель не меняет требования через revise.
- Завершение требует current same-run read receipts и покрытия строк. Проверяются транзитивные зависимости и stale ancestor, пропуски диапазонов, reset прогресса и свежесть snapshot.
- Предварительно проверен наибольший JSON snapshot с учётом escaping, hashes и revision: допустимый план помещается в полный tool envelope до любых изменений состояния.
- Registry допускает до 12 явных схем; лимиты исполнения остаются 6 model calls / 8 tool requests. Новые tools не попадают общими beans в прежние API.
- Динамический план добавляется перед каждым prompt и входит в бюджет. Completion gate работает до коммита turn; прежние режимы и callbacks сохраняют контракт.
- Независимый executable JAR прошёл четыре настоящих Ollama HTTP вызова и шесть коррелированных tools: два шага получили COMPLETED с актуальными hashes. Изменение файла редактором отражено в свежем GET snapshot. Преждевременный final остановлен PLAN_INCOMPLETE, conversation history осталась пустой.
- В ДЗ отдельно проверяются критичность required descendants, ACTIVE priority, транзитивные блокировки, стабильный порядок, maxSteps и greedy read-line budget. Предпросмотр не запускает шаги.
- В homework сохранены все 382 файла лекционной базы; исключения из сравнения — собственный README и имена приложения/артефакта.

Дополнительная проверка четырёх executable HW JAR подтвердила starter 501 и reference frontier SELECTED/BLOCKED_DEPENDENCY/OVER_BUDGET, приоритет ACTIVE и отказ на поддельных steps (400).

## Граница результата

READ_TEST означает чтение указанного диапазона, а не запуск проверки. Completed plan подтверждает только заявленные read-критерии. Поведение настоящих весов, качество плана модели и выполнение более сложной задачи за шесть вызовов не оценивались. План и raw journal находятся в RAM; бланк ручного эксперимента оставлен пустым.
