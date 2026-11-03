# ДЗ5.2. Проверить, что ссылки ответа опираются на прочитанный код

[Материалы урока](../README.md) · [Лекция](../lecture/README.md) · [Приёмочные тесты](src/test/java/dev/myfrei/repopilot/evidence/EvidencePolicyAcceptanceTest.java) · [Отчёт по ДЗ](SUBMISSION.md) · [Проверки примера](../VERIFICATION.md)

Вся база лекции готова: manual agent runtime, listFiles/searchText/readFile/gitStatus/gitDiff, проверка workspace, настоящий Git и Java25 target Stock Room. Новое приращение — проверка заявленных ссылок на код по журналу чтения конкретного run и текущей версии файла.

Единственный TODO — [EvidencePolicy.check](src/main/java/dev/myfrei/repopilot/evidence/EvidencePolicy.java). Чтение файлов, Git, ledger, decoder запроса, проверка существования run, повторный hash lookup и HTTP adapter предоставлены. Это не повторная реализация readFile.

## Готовая база и новые тесты

Из `homework`:

```bash
./mvnw test
./mvnw -Phomework -Dtest=EvidencePolicyAcceptanceTest test
```

Обычная сборка проверяет готовые функции и предоставленные adapter-тесты. Профиль homework включает acceptance для вашей policy; до реализации они выявляют TODO. После реализации выполните полную проверку:

```bash
./mvnw verify -Phomework,pgvector-it
./mvnw -f workspace-fixture/pom.xml test
npm ci
npm test
```

pgvector-it относится к унаследованному RAG и требует Docker. Само новое правило не вызывает модель, Git или БД. Fixture — отдельный Maven-проект; корневой POM не собирает его автоматически.

## Что считается свидетельством чтения

Успешный readFile записывает server-owned ReadReceipt:

```text
runId, path, fullFileHash, startLine, endLine
```

Это только целые строки, которые поместились в полный tool envelope и были возвращены агентскому runtime. Хеш SHA-256 относится ко всем UTF-8 bytes файла, включая окончания строк, а диапазон — только к фактически возвращённым строкам. listFiles, совпадение searchText и gitDiff не заменяют такой receipt.

Пример: агент запросил1..80, но в результат поместилось1..25. Receipt подтверждает1..25. Ссылка20..30 ещё не обеспечена прочитанными строками, даже если readFile вернул ok=true. Новое чтение26..30 той же версии может закрыть этот промежуток.

Stock Room — отдельный target repository. Каталог задач TaskShelf прежних tools не доказывает, что агент прочитал StockLedger.java. Не смешивайте источники только потому, что они доступны одному runtime.

## Предоставленный API

Запустите приложение в папке homework, следуя настройке fixture из лекции:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--repopilot.agent.enabled=true --repopilot.workspace.enabled=true --repopilot.workspace.root=./workspace-fixture"
```

Создайте conversation и выполните run, который действительно использует readFile. Из `/api/agent/runs/{runId}/messages` возьмите путь, fullFileHash и диапазон из tool result. Этот endpoint намеренно содержит raw transcript; metadata events/report не являются его заменой.

Новый маршрут принимает **только claims**:

```http
POST /api/agent/runs/{runId}/evidence/check
Content-Type: application/json

{
  "claims": [
    {
      "path": "src/main/java/dev/myfrei/fixture/StockLedger.java",
      "fullFileHash": "<64 hex из реально полученного readFile>",
      "startLine": 1,
      "endLine": 8
    }
  ]
}
```

Здесь значения hash и границ нужно взять из результата вашего run. Это шаблон формата, не готовое свидетельство. Не подставляйте хеш случайного файла или ожидаемые строки до фактического чтения.

Adapter:

1. Декодирует закрытый raw JSON: claims1..12, в каждом ровно path/fullFileHash/startLine/endLine. Дроби/строки вместо integer, null, дополнительные поля и request>8192 UTF-8 bytes дают400 EVIDENCE_INVALID_REQUEST.
2. Проверяет существование run; неизвестный UUID даёт404 AGENT_RUN_NOT_FOUND, активный run —409 EVIDENCE_RUN_ACTIVE.
3. Получает receipts этого run из WorkspaceService. Клиент не может прислать свои receipts или currentHashes: такие поля отвергаются.
4. Повторно получает текущий hash только для путей, для которых есть receipt той же версии и run. Несколько claims одного пути не вызывают повторного lookup. Ошибка текущего чтения оставляет hash отсутствующим.
5. Передаёт immutable metadata в policy; содержимое файлов в policy не передаётся.

До решения TODO новый endpoint возвращает501 EVIDENCE_NOT_IMPLEMENTED. Это не успешная проверка. После решения HTTP200 означает, что проверка состоялась; результат может содержать неподтверждённые claims.

Если ledger никогда не записывался либо вытеснен, endpoint даёт409 RECEIPTS_UNAVAILABLE. Это отличается от policy-статуса NOT_READ: для NOT_READ набор receipts доступен, но в нём нет нужного свидетельства. Вытесненный run не может создать частичный новый ledger тем же ID; late read остаётся unavailable. Лимиты ledger и fail-closed capacity описаны в лекции.

## Чистый контракт EvidencePolicy

Вход `EvidenceInput`:

```text
runId: UUID
claims: [{path,fullFileHash,startLine,endLine}]
reads: [{runId,path,fullFileHash,startLine,endLine}]
currentHashes: path → SHA-256
```

Готовые DTO проверяют границы:1..12 claims, до256 reads, до12 current hashes; нормализованный относительный путь до240 UTF-8 bytes, lowercase SHA-256 из64 hex, inclusive range1..100000. Коллекции копируются. Путь с абсолютным корнем, `..`, `.` или пустыми сегментами, backslash/control characters не принимается. Реальную filesystem-проверку выполняет предоставленный WorkspaceService.

Для **каждого claim**, сохраняя исходный порядок и дубликаты:

| Приоритет | Условие | Статус |
| --- | --- | --- |
| 1 | Нет receipt с точным runId, path и fullFileHash | NOT_READ |
| 2 | Такой receipt есть, но currentHashes не содержит path | UNAVAILABLE |
| 3 | Текущий hash не равен fullFileHash claim | STALE |
| 4 | Версия совпадает, но объединение подходящих read ranges не покрывает весь claim | OUTSIDE_READ_RANGES |
| 5 | Все условия подтверждены | SUPPORTED |

Приоритет обязателен. Неизвестную версию нельзя назвать STALE только по сравнению с current hash: сначала должно существовать свидетельство её чтения. При недоступном или изменившемся файле нельзя выдавать OUTSIDE_READ_RANGES вместо более ранней причины, даже если покрытие тоже неполное.

Для покрытия выбирайте только receipts того же run/path/hash. Сортировка допустима на копии. Пересечения и соседние диапазоны объединяются:

- чтения1..4,4..6,7..9 покрывают claim2..8;
- чтения1..4 и6..9 **не** покрывают3..7: строка5 отсутствует;
- чтение5..5 подтверждает ровно одну строку;
- чтение3..9 из другого run или другой версии не закрывает пропуск;
- изменение любой части полного файла меняет hash: даже неизменившийся процитированный отрывок консервативно получает STALE.

Выход `EvidenceReport(runId,checks,allSupported)`. Каждый Check сохраняет path/hash/start/end исходного claim и вычисленный status. allSupported=true только когда **все** checks SUPPORTED. Выходные списки immutable. Не менять вход, не читать filesystem из policy, не вызывать модель ради исправления неподтверждённой ссылки.

Это проверка происхождения и актуальности кода в момент проверки. Она не доказывает, что объяснение модели семантически верно, и не является атомарной блокировкой filesystem: после проверки файл может снова измениться.

## Приёмка

Восемь acceptance проверяют точное чтение, объединение пересечений/соседних ranges, пропуск, изоляцию run/path/version, приоритет STALE/NOT_READ, UNAVAILABLE, порядок/дубликаты/immutability и inclusive boundaries/input guards. Сценарии с пропуском одновременно с отсутствующим/изменившимся hash проверяют именно порядок причин.

Предоставленные adapter-тесты проверяют закрытый decoder, реальные server receipts/current hash без lookup непрочитанного пути, повторный lookup после изменения, длинный допустимый путь в другом receipt и HTTP typed errors. Они подставляют capture policy. Acceptance проверяют вашу логику; используйте оба набора для проверки policy и её подключения к API.

Для демонстрации: получите SUPPORTED по фактическому чтению; затем вручную измените учебный файл и проверьте тот же claim — STALE. Не переносите этот изменённый файл в сдаваемый baseline без объяснения. NOT_READ покажите для другой версии/пути при доступном ledger; OUTSIDE_READ_RANGES — для непокрытых строк. В тестах воспроизводите пробелы покрытия напрямую: реальная модель может выбрать другой диапазон.

Заполните [SUBMISSION.md](SUBMISSION.md). Не отключайте tests и не заменяйте недоступное свидетельство успехом.
