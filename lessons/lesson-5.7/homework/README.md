# Домашнее задание 5.7. Сравнение результатов одной проверки

В [лекции](../lecture/README.md) RepoPilot выполняет закреплённый контракт после отдельного решения человека, сохраняет результат и проверяет актуальность входной версии. В этом задании вы добавляете **диагностический отчёт о согласованности нескольких наблюдений**.

Два запуска на одном входе дали разные exit code. Это повод исследовать среду или проверку. Но timeout нельзя записать как обычный провал теста, а два одинаковых report ID не являются двумя отдельными запусками. Сравнивать результаты разных версий или образов тоже нельзя.

Новый endpoint анализирует уже сохранённые отчёты. Он не запускает Docker, не создаёт запрос разрешения, не повторяет retry-логику и не решает, прошёл ли текущий проект проверку.

## Что уже готово

В `homework` полностью сохранён проект `lecture`, включая runner, отдельное одобрение каждого запуска и существующие ограничения.

Предоставлены:

- строгий decoder запроса с идентификаторами отчётов;
- `VerificationStabilityChecker`, загружающий факты из `VerificationService`;
- неизменяемые DTO, controller, конфигурация и обработчик ошибок;
- три теста предоставленных адаптеров;
- восемь приёмочных тестов новой классификации.

В policy **не передаются** stdout, stderr, свободный `reason`, текст вопросов и модельные утверждения об успехе.

Единственный TODO:

```text
src/main/java/dev/myfrei/repopilot/stability/VerificationStabilityPolicy.java
```

Реализуйте `check(VerificationStabilityInput input)`.

## Запрос

```http
POST /api/verification/conversations/{id}/stability/check
Content-Type: application/json
```

```json
{"reportIds":["ea14660a-d53c-4702-b930-6a665434cdf1"]}
```

Используйте реальные ID своего диалога; UUID выше показывает только форму. Принимаются от 1 до 8 канонических UUID. Повторы допустимы: policy должна их распознать. Любые поля с exit code, версиями, статусами, логами или готовыми отчётами от клиента запрещены.

Некорректный JSON даёт HTTP 400, неизвестный диалог или отчёт — HTTP 404. Отчёт другого существующего диалога получает `OUT_OF_SCOPE`; его ключ и статистика не раскрываются.

Policy получает только серверные метаданные:

```java
record VerificationStabilityInput(UUID conversationId, List<Sample> samples) {
    record Sample(
        UUID id, UUID conversationId, String command,
        String inputRevision, String imageId, String status,
        Integer exitCode, boolean inputsStillCurrent,
        boolean outputTruncated
    ) {}
}
```

`inputRevision` относится к входу закреплённого контракта лекции. Это не Git commit и не утверждение о версии всех файлов репозитория. `imageId` — точный идентификатор образа. В текущем продукте команда одна: `STOCKROOM_CONTRACT_V1`; ДЗ не добавляет новые команды или профили сборки.

`inputsStillCurrent` — сохранённая серверная проверка входа на момент завершения того запуска. Новый endpoint не перечитывает сегодняшний workspace. Его результат — историческое сравнение, а не разрешение объявить текущий код проверенным.

## 1. Исключите повторы и чужой scope

Обходите samples в порядке запроса:

1. Если report ID уже встречался — `DUPLICATE`.
2. Иначе, если `sample.conversationId` отличается от входного — `OUT_OF_SCOPE`.
3. Иначе включите sample в группу и классифицируйте его исход.

Первое вхождение ID считается встреченным даже при `OUT_OF_SCOPE`. Повтор не должен увеличивать ни один счётчик и не создаёт новую группу.

## 2. Определите исход наблюдения

| Условие | Статус sample |
| --- | --- |
| `inputsStillCurrent=true`, статус `PASS`, exit code равен 0 | `CONCLUSIVE_PASS` |
| `inputsStillCurrent=true`, статус `FAIL`, exit code присутствует и не равен 0 | `CONCLUSIVE_FAIL` |
| Всё остальное | `INCONCLUSIVE` |

`TIMEOUT`, `ERROR`, `STALE`, отсутствующий exit code и противоречивые метаданные не превращаются в провал теста. Например, `PASS` с exit code 1 — `INCONCLUSIVE`.

`outputTruncated=true` само по себе не отменяет достоверно полученный exit code. Логи могли быть ограничены после завершения процесса. Их полнота учитывается отдельным счётчиком, а не подменяет исход исполнения.

## 3. Разделите точные контракты

Ключ группы — **точное совпадение трёх строк**:

```text
(command, inputRevision, imageId)
```

Другая версия входа или другой образ создают другую группу. Не объединяйте по одному имени команды, части hash или сходству текста.

Порядок групп — первое появление их ключа во входном списке. Внутри группы считайте только уникальные отчёты разрешённого диалога:

- `passCount`;
- `failCount`;
- `inconclusiveCount`;
- `sampleCount` — сумма этих трёх счётчиков;
- `truncatedOutputs` — число включённых samples с `outputTruncated=true`.

Последний счётчик охватывает и неопределённые результаты. Повторы и чужие отчёты не входят в него.

## 4. Классифицируйте группу

Применяйте первый подходящий вариант:

| Приоритет | Условие | Статус группы |
| --- | --- | --- |
| 1 | Есть хотя бы один conclusive PASS и один conclusive FAIL | `INCONSISTENT` |
| 2 | Есть хотя бы один inconclusive sample | `INCONCLUSIVE` |
| 3 | Conclusive samples меньше двух | `INSUFFICIENT` |
| 4 | Все conclusive samples — PASS | `CONSISTENT_PASS` |
| 5 | Все conclusive samples — FAIL | `CONSISTENT_FAIL` |

Примеры:

- PASS + PASS → `CONSISTENT_PASS`.
- PASS + FAIL → `INCONSISTENT`.
- PASS + TIMEOUT → `INCONCLUSIVE`.
- PASS + PASS + ERROR → `INCONCLUSIVE` в защитном входе policy.
- PASS + FAIL + ERROR → `INCONSISTENT`: противоречие уже наблюдалось.
- Один PASS → `INSUFFICIENT`.

В текущем runtime максимум два запуска на одном контракте входа и образе; ДЗ не обходит этот предел. Некоторые тесты чистой policy используют более длинные защитные наборы метаданных, чтобы однозначно закрепить приоритеты.

`CONSISTENT_PASS` означает только согласованные наблюдения этой выборки. Он не доказывает вечную стабильность проверки. `INCONSISTENT` не устанавливает причину и не доказывает наличие flaky-теста: для этого нужно отдельное расследование.

## Результат

```java
record VerificationStabilityReport(
    UUID conversationId, List<SampleCheck> checks, List<Group> groups
) {
    record SampleCheck(UUID reportId, String status) {}
    record Group(
        String command, String inputRevision, String imageId,
        String status, int sampleCount, int passCount, int failCount,
        int inconclusiveCount, int truncatedOutputs
    ) {}
}
```

`checks` содержит ровно один элемент на каждый sample в порядке запроса, включая повторы и отказы. `groups` содержит только разрешённые уникальные наблюдения. Списки результата неизменяемы. Текст логов и причина runner-ошибки в отчёт не попадают.

## Проверки

```bash
./mvnw test
./mvnw -Phomework -Dtest=VerificationStabilityPolicyAcceptanceTest test
./mvnw -Phomework verify
```

Без `homework` исключён только новый приёмочный класс. В каркасе базовые тесты и три предоставленных теста зелёные; восемь приёмочных ожидаемо падают из-за TODO. После реализации должны пройти все проверки.

Для PostgreSQL нужен Docker:

```bash
./mvnw -Phomework,pgvector-it verify
npm ci
npm test
./mvnw -f workspace-fixture/pom.xml test
```

Новая policy не требует запуска Docker-контейнера или модели. Интеграционный Docker-сценарий лекции запускается отдельно по её инструкции.

## Ручной сценарий

Запустите проект с теми же семью feature flags, что в лекции: `agent`, `workspace`, `controlled`, `skills`, `plans`, `compaction`, `verification` через `repopilot.<имя>.enabled`.

Получите реальные отчёты:

```bash
curl -sS http://localhost:8080/api/verification/conversations/REPLACE_WITH_CONVERSATION_UUID/reports
```

Два отдельных запуска одной версии требуют двух отдельных ручных решений по правилам лекции. Повтор одного ID не заменяет второй запуск. Затем отправьте их ID:

```bash
curl -sS http://localhost:8080/api/verification/conversations/REPLACE_WITH_CONVERSATION_UUID/stability/check \
  -H 'Content-Type: application/json' \
  -d '{"reportIds":["REPLACE_WITH_REPORT_UUID_1","REPLACE_WITH_REPORT_UUID_2"]}'
```

До реализации TODO endpoint возвращает HTTP 501 с кодом `STABILITY_NOT_IMPLEMENTED`. После реализации корректный запрос даёт HTTP 200 независимо от диагноза группы.

Проверьте повтор ID, одиночное наблюдение и отчёты разных версий. Число runner executions и исходные отчёты после preview должны сохраниться. Не пытайтесь искусственно получить противоположный результат редактированием метаданных: отрицательные случаи воспроизводятся автоматическими тестами на предоставленных серверных фикстурах.

## Приёмка

- Восемь приёмочных тестов и базовые проверки зелёные.
- Дубликаты не увеличивают выборку; чужой scope не раскрывает ключ группы.
- Точное разделение версий и образов сохранено.
- Timeout, ошибка инфраструктуры и устаревший вход не считаются провалом теста.
- Соблюдается приоритет противоречия и неопределённых наблюдений.
- Нет новых запусков, разрешений, записей или попыток обхода лимитов.
- В [SUBMISSION.md](SUBMISSION.md) описана граница исторического сравнения.

Необязательное усложнение: UI группировки истории с переходом к исходным отчётам. Классификацию должен выполнять сервер.

## Читать

- [Java 25: LinkedHashMap и порядок](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/LinkedHashMap.html).
- [Java 25: неизменяемые копии List](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html).
- [Docker: ограничения контейнера](https://docs.docker.com/engine/containers/run/).
