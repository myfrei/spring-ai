# Проверка урока 5.2

Дата:2026-10-31. Java25.0.5+8, Maven Wrapper3.9.11, Boot4.1.2, SpringAI2.0.2, Git2.50.1 (Apple Git155), Node24.16.0/npm11.13.0. Новый файловый/Git путь проверен на настоящей локальной filesystem и временных Git repositories; model responses — HTTP fixtures.

## Матрица

| Срез | Проверки | Результат |
| --- | --- | --- |
| lecture | 410 Java +39 pgvector IT +104 JS | PASS |
| homework, готовая база | 413 Java +39 pgvector IT +104 JS | PASS |
| Новые EvidencePolicy acceptance в каркасе | 8 ERROR из-за одного TODO | Ожидаемый RED |
| Временный эталон homework, -Phomework | 421 Java +39 pgvector IT | PASS |
| workspace-fixture, lecture/homework | 4 Java tests в каждом | PASS |
| Новый evidence endpoint, private solution/starter | Настоящий Boot REST +Ollama HTTP fixture +файлы | PASS |

410 включает370 из5.1 и40 workspace-проверок: path/file/output/ledger, настоящий Git, process timeout/noisy stdout-stderr, adapters/config и5 новых HTTP agent scenarios. Последний полный verify выполнен после исправлений search budget, ledger eviction и config.worktree. Три предоставленных EvidenceAdapterTest проходят до заполнения TODO; восемь acceptance проверяют независимую policy. Reference отличается от сдаваемого исходного кода только EvidencePolicy.java; решения в course/homework нет.

## Повторение

Из lecture или homework:

```bash
./mvnw verify -Ppgvector-it
./mvnw -f workspace-fixture/pom.xml test
npm ci
npm test
```

Первая команда требует Docker для прежнего RAG. Новые tests файлов/Git/агента используют temporary directories/repositories и Ollama HTTP fixture. Реальные веса не скачиваются; shipped workspace-fixture не содержит .git, создание учебной истории выполняет человек командами из лекции. Fixture имеет отдельный POM: app не собирает его автоматически.

В homework:

```bash
./mvnw -Phomework -Dtest=EvidencePolicyAcceptanceTest test
```

До решения ожидаются8 TODO errors. После решения:

```bash
./mvnw verify -Phomework,pgvector-it
```

Унаследованный mcp-host-it запускается отдельно по команде4.4/5.1 с taskshelf.http.jar; его исходники/профиль сохранены. В текущем срезе повторно проверены39 pgvector IT и полная fast suite; последняя отдельная проверка4 MCP IT описана в [5.1](../lesson-5.1/VERIFICATION.md).

## Файлы, Git и ограничение работы

- Разрешены только README.md, pom.xml и допустимые текстовые descendants src/. Проверены absolute/traversal/backslash/control/hidden/secret paths, symlinks, missing files, directories, invalid UTF-8/binary и oversized file/result. Настроенный root канонизируется; это не OS sandbox и не atomic snapshot.
- readFile возвращает фактические inclusive ranges, SHA-256 полного исходного файла, totalLines/bytes, content, truncated/rangeComplete. Крупная первая строка даёт OUTPUT_LIMIT без receipt; partial output содержит только целые строки.
- До ledger проверяется полный success envelope≤4096 UTF-8 bytes, а не только data JSON. Доказательство не записывается для данных, которые runtime отверг бы по объёму.
- Search literal/case-sensitive; bounded scan512 entries/depth12, file≤65536 bytes. Каждая попытка чтения резервирует65537 bytes до decode, включая binary failure; общий reservation≤2MiB. Поэтому допускается до31 attempted reads, scanComplete=false при ранней остановке. searchedBytes — успешно прочитанный текст, readBudgetBytes — консервативный upper-bound reservation. Это не performance benchmark.
- Git status/diff работают в настоящем temp repo: tracked/untracked/rename/deleted/staged/worktree, Unicode paths, literal pathspec, no stdout/stderr deadlock и timeout. Index/worktree не изменяются этими read commands. Пустой diff не используется как доказательство чистоты untracked/out-of-scope файлов.
- Процесс получает фиксированный argv, без shell и model-selected command. Отключены pager, optional locks, fsmonitor, hooks, external diff/textconv и inherited global/system/GIT_* configuration. Ограничены stdout/stderr и ожидание процесса; JSON patch отдельно ограничен4096.
- .git symlinks/worktree-file/alternates/commondir и include/filter configuration отвергаются. Подтверждённый обход через `.git/config.worktree` закрыт: overlay теперь отклоняется до Git, harmless clean-filter marker не создаётся. Это проверено regression и независимым повтором before/after.
- Ledger ограничен128 recent runs и256 уникальными receipts/run. Evicted run IDs сохраняются в bounded retired set256: поздний read не создаёт частичный authoritative ledger. При заполнении retired budget новое evidence отклоняется fail-closed; старые tombstones не теряются. Отсутствующий/retired ledger выдаёт RECEIPTS_UNAVAILABLE.

## Настоящий агентский путь

Новая HTTP suite провела run через все пять workspace tools с восемью зарегистрированными tools вместе с TaskShelf. Выполнено6 tools за4 chat requests; модель получила реальные file paths/ranges/full hashes кода и теста. Ledger содержит2 read receipts этого run. Проверены ошибки доступа, отсутствие Git, разные run IDs и full-envelope bound.

Stock Room и TaskShelf — разные учебные источники. Прежний getTask не заменяет чтение StockLedger.java. Наличие Git status также не доказывает, что код изучен или тесты запущены.

## Новое ДЗ: доказательства через настоящий REST

В отдельной private копии homework заменена только EvidencePolicy. Реальное приложение выполнило readFile через AgentEngine и OllamaChatModel HTTP fixture, завершило run и обработало новый EvidenceController. JSON-ответы подтвердили:

| Сценарий | Наблюдение |
| --- | --- |
| Claim покрыт реальным чтением, current hash совпадает |200 SUPPORTED |
| Claim включает непрочитанные строки |200 OUTSIDE_READ_RANGES |
| Файл физически изменён после readFile |200 STALE |
| Другой существующий, но непрочитанный файл |200 NOT_READ |
| Файл физически удалён после readFile |200 UNAVAILABLE |
| Клиент прислал fake reads/currentHashes |400 EVIDENCE_INVALID_REQUEST |
| Run завершён без чтения |409 RECEIPTS_UNAVAILABLE |
| Исходная незаполненная policy |501 EVIDENCE_NOT_IMPLEMENTED |

Восемь acceptance отдельно проверяют union пересечений/соседних ranges без дыр, exact run/path/version, приоритет причин, сохранение порядка/дубликатов и immutability. В частности, gap+missing current hash →UNAVAILABLE; gap+changed hash →STALE; отсутствие exact receipt+changed current hash →NOT_READ. Independent compiled probe подтвердил эти случаи.

Исправлено согласование path limits: metadata DTO использует те же240 UTF-8 bytes, что и workspace. Реальный server-owned receipt с ASCII path210+ chars не ломает проверку другого короткого claim. Это покрыто adapter regression и независимым запуском.

## Границы

SUPPORTED означает только подтверждённые provenance/version/range в момент проверки. Он не доказывает смысл объяснения модели, полноту исследования, отсутствие багов или успешное выполнение tests. Файл может измениться сразу после ответа: блокировки версии и атомарного filesystem snapshot здесь нет.

Настоящие Ollama weights, качество выбора tools и русские объяснения проверяются вручную по [бланку](lecture/evaluation/Workspace-Read-Experiment.md). В tests используются контролируемые model responses. Запись кода, произвольный shell, approvals и Skills ещё не реализованы в runtime.
