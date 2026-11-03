# Домашнее задание 5.10. Переносимый пакет аудита

В [лекции](../lecture/README.md) RepoPilot проходит сквозную задачу и показывает серверный отчёт с реальными evidence. В этом задании вы добавляете **скачиваемый JSON-пакет для ревью вне приложения**.

Обычный отчёт содержит diff, служебные ID и время формирования. Переносить весь объект опасно и неудобно. Новый экспорт берёт только разрешённые структурированные факты, фиксирует пропуски и добавляет контрольную сумму.

Пакет не повторяет final gate, не запускает проверки и не переводит задачу в `DONE`. Его SHA-256 подтверждает целостность содержимого, **не подлинность источника**: это не подпись и не аттестация.

## Что уже готово

В `homework` сохранена вся лекционная база. Предоставлены controller скачивания, строгий decoder, adapter серверных metadata, DTO и обработчик ошибок. Есть восемь приёмочных тестов и три теста адаптеров.

Единственный TODO:

```text
src/main/java/dev/myfrei/repopilot/auditexport/PortableAuditPolicy.java
```

Реализуйте `export(PortableAuditInput input)`.

Адаптер получает `CapstoneService.report(sessionId)` и связанные серверные verification/patch records. Он **не передаёт policy** diff, source, stdout/stderr, reason text, вопросы, ответы модели, token, server URL, conversation ID или timestamps. Новое поле в исходном отчёте не должно автоматически попасть в экспорт.

## Запрос и ответ

```http
POST /api/capstone/sessions/{id}/audit/export
Content-Type: application/json
```

```json
{"maxBytes":8192}
```

Клиент передаёт только целый бюджет от 1 до 16384 байтов. Сам отчёт, `state`, hash и evidence прислать нельзя. Некорректный запрос даёт HTTP 400; неизвестный capstone session — HTTP 404.

Успешный ответ — **сам JSON-документ**, а не JSON-строка внутри другого ответа:

```http
Content-Type: application/json;charset=UTF-8
Content-Disposition: attachment; filename="repopilot-audit.json"
X-Audit-SHA256: <checksum payload>
```

DTO `PortableAuditPacket` содержит `json`, `sha256`, `utf8Bytes`. Controller скачивания уже реализован.

## Обязательная схема версии 1

Документ имеет ровно два верхних поля:

```json
{"payload":{},"sha256":"64 lowercase hex digits"}
```

В `payload` разрешены только:

| Поле | Содержимое |
| --- | --- |
| `schemaVersion` | Число `1` |
| `format` | `REPOPILOT_AUDIT` |
| `integrity` | `CHECKSUM_ONLY` |
| `task` | Объект `id`, `digest` |
| `state` | Исходное `INCOMPLETE`, `STALE` или `DONE` |
| `imageId` | Точный `sha256:<64 hex>` |
| `provenance` | Поля receipt из таблицы ниже |
| `gates` | Объект `code → passed` |
| `revisions` | Объект `baseline`, `result`, `current` |
| `evidence` | Объект `baseline`, `finalVerification`, `patch` |
| `details` | Только сохранённые необязательные разделы |
| `omittedFields` | Имена исключённых необязательных разделов |

`provenance` включает ровно `serverName`, `toolName`, `protocolVersion`, `initializeCalls`, `listCalls`, `toolCalls`, `taskDigest`. `importedAt` не экспортируется.

`revisions` сохраняет исходные три значения, включая `null`. Не путайте raw file hash patch с manifest revision verification: экспортёр копирует поля с указанными именами, а не пересчитывает или приравнивает их.

Verification evidence, если присутствует, включает только `status`, `exitCode`, `inputRevision`, `imageId`, `inputsStillCurrent`. Patch evidence — только `status`, `baseHash`, `resultHash`, `operationHash`.

Отсутствующие baseline/final/patch evidence представлены **явным `null`**. Это допустимо для честного `INCOMPLETE` или `STALE` пакета. Наличие скачиваемого файла не означает завершённую задачу.

## Проверка формы обязательных фактов

Если task ID/digest/state/image, provenance, список gates или его элемент отсутствуют, бросайте `AuditExportProblem("AUDIT_REQUIRED_FACTS_MISSING")`.

Некорректная форма даёт `AUDIT_INVALID_FACTS`:

- task ID: `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`;
- hash: 64 строчные hex-цифры; image: `sha256:` и такой hash;
- server/tool names: `[A-Za-z][A-Za-z0-9_.-]{0,63}`, без URL;
- protocol version: форма `YYYY-MM-DD`;
- counters provenance: целые 0–32;
- gate code: `[A-Z][A-Z0-9_]{0,63}`, без повторяющихся кодов;
- состояния task, verification и patch — документированные серверные состояния;
- ненулевые revision/evidence hashes должны иметь правильную форму.

Здесь проверяется схема переносимого документа. Не повторяйте бизнес-правила final gate: не выводите `DONE` из exit code, не исправляйте противоречивое состояние и не превращайте false gate в true. Экспорт сохраняет серверные факты, включая неудачные.

## Два необязательных раздела

В `details` допустимы только:

1. `relativeSourcePath`.
2. `evidenceIds`.

Путь может быть `README.md`, `pom.xml` или находиться под `src/`. Он не должен быть абсолютным, содержать `\`, `:`, `?`, `#`, control characters, пустые/скрытые/`.`/`..` segments. Максимум 240 UTF-8 байтов. Недопустимый или отсутствующий путь исключается целиком.

`evidenceIds` допускает только ключи `session`, `prepareJob`, `reviewJob`, `baselineReport`, `passingReport`, `patch` с ненулевыми UUID. Пустая/отсутствующая map, неизвестный ключ или null value исключают весь раздел. Нельзя переносить неизвестный ключ в пояснение об исключении: он сам может содержать чувствительный текст.

Для исключённого раздела добавьте его **фиксированное имя** в `omittedFields`. Порядок этого списка всегда:

```text
evidenceIds → relativeSourcePath
```

Других optional полей или произвольных notes нет.

## Канонизация, checksum и бюджет

1. Сформируйте объект payload строго по allowlist.
2. Получите его канонический JSON: `AgentJson.canonical(payload)`.
3. Посчитайте `Hashes.sha256(canonicalPayload)` — UTF-8 SHA-256 именно payload.
4. Сформируйте canonical envelope `{payload, sha256}`.
5. Измерьте **полный envelope** в UTF-8 байтах.

Ключи объектов канонически отсортированы; порядок смысловых массивов сохраняется. Gates и evidence IDs являются объектами, поэтому перестановка их входного порядка не меняет байты пакета. Время, nonce и случайный ID экспорта не добавляются.

Hash не включает сам себя. `omittedFields` находится внутри payload и включён в checksum.

Если документ не помещается, исключайте optional разделы в порядке **evidenceIds, затем relativeSourcePath**. После каждого исключения заново формируйте payload, checksum, envelope и фактический размер. Список исключений также занимает байты.

Когда все optional разделы исключены, а полный документ всё ещё велик, бросайте `AUDIT_MAX_BYTES_TOO_SMALL`. Нельзя обрезать JSON/UTF-8, удалять обязательный gate или возвращать частичное доказательство.

Все ошибки policy дают HTTP 409 без файла. Контрольная сумма и `utf8Bytes` возвращённого пакета должны соответствовать его окончательным байтам.

## Проверки

```bash
./mvnw test
./mvnw -Phomework -Dtest=PortableAuditPolicyAcceptanceTest test
./mvnw -Phomework verify
npm ci
npm test
```

Без `homework` исключён только новый приёмочный класс. База и три предоставленных теста зелёные; восемь приёмочных в каркасе ожидаемо падают из-за TODO.

Для PostgreSQL требуется Docker:

```bash
./mvnw -Phomework,pgvector-it verify
./mvnw -f workspace-fixture/pom.xml test
```

Новая policy не требует модели, MCP-сервера или нового Docker execution. Реальный capstone сценарий проверяется отдельно по лекции.

## Ручной экспорт

Запустите проект с десятью флагами лекции, включая `sessions` и `capstone`. Получите реальный capstone session ID и сохраните файл:

```bash
curl -sS http://localhost:8080/api/capstone/sessions/REPLACE_WITH_SESSION_UUID/audit/export \
  -H 'Content-Type: application/json' \
  -d '{"maxBytes":8192}' \
  -o repopilot-audit.json
```

До TODO endpoint возвращает HTTP 501 с `AUDIT_NOT_IMPLEMENTED`; проверьте HTTP status перед тем, как считать скачанное успешным пакетом.

Повторите экспорт одинаковых фактов: байты должны совпасть. Уменьшите бюджет, проверьте `omittedFields`, затем слишком маленький бюджет и HTTP 409. Убедитесь, что в пакете нет diff, source, логов, absolute paths, URL и timestamps. Исходный отчёт, approvals и число проверок после экспорта не меняются.

Для независимого воспроизведения нужны отдельно source, образ и исходные полные отчёты. Unsigned packet — переносимый снимок для ревью. Любой может изменить payload и пересчитать checksum; он не заменяет доверие к серверу или подпись.

## Приёмка

- Восемь приёмочных и базовые проверки зелёные.
- Строгая allowlist и явные `null`/omissions сохранены.
- Нет повышения `INCOMPLETE`/`STALE` до `DONE`.
- Canonical bytes и checksum воспроизводимы.
- Полный UTF-8 бюджет соблюдён без усечения proof.
- Required missing/invalid/too-small случаи закрываются явно.
- В [SUBMISSION.md](SUBMISSION.md) проверена граница checksum и подлинности.

## Читать

- [Java 25: MessageDigest](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/MessageDigest.html).
- [Java 25: UTF-8](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/charset/StandardCharsets.html).
