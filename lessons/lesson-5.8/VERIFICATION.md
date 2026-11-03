# Проверка урока 5.8

Дата: 2026-11-03. Java Oracle 25.0.5.0.3+1-LTS-2, Maven Wrapper 3.9.11, Spring Boot 4.1.2, Spring AI 2.0.2, Docker 29.4.0, Node 24.16.0. Использованы настоящие Boot HTTP, файловая система и Docker. Ответы parent/child модели задаются Ollama HTTP fixtures.

## Матрица

| Срез | Java | pgvector IT | Runner Docker IT | JS | Stock Room |
| --- | --- | --- | --- | --- | --- |
| lecture | 535 PASS | 39 PASS | 6 PASS | 104 PASS | 4 PASS |
| homework, готовая база | 538 PASS | 39 PASS | Исходники идентичны lecture | 104 PASS | 4 PASS |
| Временный эталон homework | 546 PASS | 39 PASS | Изменена только readonly policy | База без изменений | База без изменений |
| Приёмочные в каркасе | 8 TODO errors | Не применимо | Не применимо | Не применимо | Не применимо |

Все проверки завершились без failures/errors/skips. Новых проверок 23: 6 Engine guard/fork, 9 budget/provenance и 8 Boot HTTP. Три предоставленных adapter-теста ДЗ зелёные до заполнения TODO. Частный эталон отличается только ResearchCheckPlanPolicy и не включён в курс.

## Повторение

Сначала соберите trusted image по лекции; используйте собственный фактический ID:

```bash
docker build --pull=false --iidfile /tmp/repopilot58-image.id \
  -t repopilot-stockroom-contract:lesson58 verification-runner
export REPOPILOT_VERIFIER_IMAGE_ID="$(cat /tmp/repopilot58-image.id)"
./mvnw clean verify -Ppgvector-it
./mvnw verify -Prunner-it -Dverification.image="$REPOPILOT_VERIFIER_IMAGE_ID"
npm ci
npm test
./mvnw -f workspace-fixture/pom.xml test
```

В этой проверке повторно использован неизменённый image ID из 5.7: `sha256:5d1a1de313addfd5d774ad47a3b7a87e670b6314468554ae1088dae227fbb7ff`. Интеграционные профили запускались отдельно. Унаследованный mcp-host-it сохранён, но отдельно повторно не запускался.

В homework:

```bash
./mvnw -Phomework -Dtest=ResearchCheckPlanPolicyAcceptanceTest test
```

До решения ожидаются восемь UnsupportedOperationException из одного TODO. После решения:

```bash
./mvnw clean verify -Phomework,pgvector-it
./mvnw verify -Phomework,runner-it -Dverification.image="$REPOPILOT_VERIFIER_IMAGE_ID"
```

## Подтверждённое поведение

- Child получает fresh AgentStore/conversation, три явных инструмента чтения и отдельный prompt. Нет parent history, Skills, write, approvals, verification или рекурсивного делегирования. В parent доступны 14 схем при registry cap16; прежние API сохранены.
- Строгий bounded child JSON проверяется по текущему hash и фактически доставленным child read ranges. Неверный отчёт не переносит statements в принятые findings.
- Родитель обязан перечитать принятые цитаты в собственном run и той же версии. Child receipt не разрешает patch. Проверены stale, отсутствующее parent reading и отказ в запрещённых child tools.
- Общий admission budget учитывает parent и child. Зарезервированные оценки отделены от provider usage; неизвестное usage не заменяется эвристикой. Глубина1, максимум один child и три model calls ребёнка.
- HTTP-тест отменил parent при RUNNING child: после освобождения blocking response оба остановились CANCELLED, поздний child tool не выполнился. Это кооперативная отмена, а не мгновенный сетевой abort.
- Независимый executable JAR выполнил четыре parent и два child model calls. Подтверждены 14/3 схемы, отсутствие private parent marker в child prompt и отдельный child journal. Общий отчёт: 6 model calls, 5 tool admissions, provider usage48/18 при полной известности.
- В отдельном run завершённый READ-план без повторного чтения child citation остановился RESEARCH_NOT_RECHECKED. Общий лимит2 разрешил ровно два фактических model HTTP calls и остановил исполнение RESEARCH_SHARED_MODEL_LIMIT.
- Actual Boot proof ДЗ: starter preview501; эталон выдал NEEDS_READ, частичный line budget и deferred remainder; после настоящего чтения в том же активном parent — ALREADY_READ. Внешнее изменение дало STALE без задач. Preview не изменил journal, files, provider-call count или receipts; поддельные parentReads отклонены400.
- В homework сохранены все 436 файлов лекционной базы; версия verifier/лаборатория/старые проверки не удалялись.

## Граница результата

Проверка цитаты подтверждает происхождение данных, а не истинность statement. Перечитывание само по себе не доказывает правильное понимание кода. Делегирование синхронное: отдельный контекст не означает параллельное выполнение.

Отмена может ждать текущий blocking model call и последующую identity-проверку до их timeout. Локальная сумма token reservations не является точным tokenizer cap. Read-plan ДЗ планирует строки, а не гарантирует помещение полного результата в byte budget. Настоящие веса, экономия времени и улучшение качества исследования не измерялись; эксперимент оставлен студенту.
