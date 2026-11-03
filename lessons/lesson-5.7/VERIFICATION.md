# Проверка урока 5.7

Дата: 2026-11-03. Host Java Oracle 25.0.5.0.3+1-LTS-2, Maven Wrapper 3.9.11, Spring Boot 4.1.2, Spring AI 2.0.2, Docker 29.4.0, Node 24.16.0. Модельные ответы задаются Ollama HTTP fixtures. Изолированный runner действительно компилирует и исполняет проверяемый Java-код в Docker.

## Матрица

| Срез | Java | pgvector IT | Runner Docker IT | JS | Stock Room |
| --- | --- | --- | --- | --- | --- |
| lecture | 512 PASS | 39 PASS | 6 PASS | 104 PASS | 4 PASS |
| homework, готовая база | 515 PASS | 39 PASS | Исходники идентичны lecture | 104 PASS | 4 PASS |
| Временный эталон homework | 523 PASS | 39 PASS | Изменена только readonly policy | База без изменений | База без изменений |
| Приёмочные в каркасе | 8 TODO errors | Не применимо | Не применимо | Не применимо | Не применимо |

Полные проверки закончились без failures/errors/skips. К базе 5.6 добавлены 17 быстрых проверок и 6 отдельных настоящих Docker IT. Три предоставленных adapter-теста ДЗ зелёные до заполнения TODO. Эталон отличается от homework только VerificationStabilityPolicy и находится вне курса.

## Образ и повторение

В проверке использован образ:

```text
sha256:5d1a1de313addfd5d774ad47a3b7a87e670b6314468554ae1088dae227fbb7ff
```

Base digest закреплён в Dockerfile. Для своего запуска сначала соберите образ и используйте фактический локальный ID:

```bash
docker build --pull=false --iidfile /tmp/repopilot57-image.id \
  -t repopilot-stockroom-contract:lesson57 verification-runner
export REPOPILOT_VERIFIER_IMAGE_ID="$(cat /tmp/repopilot57-image.id)"
./mvnw clean verify -Ppgvector-it
./mvnw verify -Prunner-it -Dverification.image="$REPOPILOT_VERIFIER_IMAGE_ID"
npm ci
npm test
./mvnw -f workspace-fixture/pom.xml test
```

Профили pgvector-it и runner-it запускались отдельно; каждый имеет свой набор includes. Default Java tests не требуют Docker runner. В homework после выполнения задания:

```bash
./mvnw -Phomework -Dtest=VerificationStabilityPolicyAcceptanceTest test
./mvnw clean verify -Phomework,pgvector-it
./mvnw verify -Phomework,runner-it -Dverification.image="$REPOPILOT_VERIFIER_IMAGE_ID"
```

До решения первая команда ожидаемо даёт восемь UnsupportedOperationException из одного TODO. Прежний отдельный mcp-host-it сохранён, но в этом срезе повторно не запускался.

## Подтверждённое поведение

- Runner получает только snapshot StockLedger.java, фиксированный command и image ID. POM, wrapper, scripts, .m2 и Docker socket из target не исполняются и не монтируются.
- Настоящие Docker IT проверили non-root, readonly input/root filesystem, отсутствие внешней сети и socket, writable build tmpfs, ресурсные настройки, timeout с удалением owned container, ограниченный вывод и stale/replay.
- Trusted parent выполняет assertions по результатам отдельного target child. System.exit(0) в target не дал ложный PASS.
- Approval одноразовое, ограничено сроком и точной входной версией. После patch требуется новое разрешение проверки. На одну версию допускается не больше двух исполнений; повторные FAIL приводят к NO_PROGRESS, неизвестный исход не повторяется автоматически.
- Отдельный executable Boot JAR прошёл настоящий FAIL exit10 → модельное чтение отчёта/исходника → proposal → ручной APPLIED → новая Docker-проверка PASS exit0. Пока модельный run был COMPLETED, typed verification оставался CHECKED_FAIL; после новой проверки стал CHECKED_PASS. Старое решение и изменённая после запроса версия получили HTTP 409.
- Два executable JAR ДЗ создали по два настоящих PASS одной версии. Starter preview вернул 501; эталон — CONSISTENT_PASS. Дубликат не увеличил выборку, чужая conversation не раскрыла group key, поддельный status отклонён 400. Preview не создал новых отчётов или запусков.
- Все 416 файлов lecture сохранены в homework; отдельный failing lab, verifier sources и runner-it профиль также сохранены.

## Граница результата

Проверяются пять фиксированных свойств одного StockLedger.java. Это не полный Maven build и не весь JUnit-набор target. PASS относится к inputRevision и imageId; исторический inputsStillCurrent отражает finishedAt, а live summary проверяет версию заново.

Контейнерные ограничения не доказывают безопасность произвольного враждебного проекта. Настоящие веса LLM и качество предлагаемого исправления не оценивались; бланк ручного эксперимента оставлен пустым. Reports, approvals и runs хранятся в RAM.
