# Проверка урока 2.2

Дата: 2026-10-25.

## Среда

macOS aarch64, Temurin 25.0.5+8, Maven 3.9.11 / Wrapper 3.3.4, Boot 4.1.2, Spring AI 2.0.2, SQLite JDBC 3.53.4.0. Node.js 24.16.0, npm 11.13.0, jsdom 27.4.0. Новых внешних зависимостей нет; системная Java не переключалась.

## Lecture

Успешно выполнены ./mvnw package, npm ci и npm test. Создан исполняемый JAR с manifest и тремя Markdown-документами.

| Java-набор | Число | Падения / ошибки / пропуски |
| --- | --- | --- |
| ChatControllerTest | 8 | 0 / 0 / 0 |
| ChatServiceTest | 14 | 0 / 0 / 0 |
| ChatStreamControllerTest | 2 | 0 / 0 / 0 |
| ChatStreamServiceTest | 15 | 0 / 0 / 0 |
| OllamaHttpIntegrationTest | 5 | 0 / 0 / 0 |
| StreamingHttpIntegrationTest | 7 | 0 / 0 / 0 |
| SystemPromptTest | 2 | 0 / 0 / 0 |
| ContextAssemblerTest | 9 | 0 / 0 / 0 |
| ContextHttpIntegrationTest | 7 | 0 / 0 / 0 |
| CorpusCatalogTest | 7 | 0 / 0 / 0 |
| ConversationChatServiceTest | 5 | 0 / 0 / 0 |
| ConversationGateTest | 2 | 0 / 0 / 0 |
| ConversationHttpIntegrationTest | 6 | 0 / 0 / 0 |
| ConversationMemoryFactoryTest | 4 | 0 / 0 / 0 |
| SqliteHistoryTest | 9 | 0 / 0 / 0 |
| IndexArtifactStoreTest | 4 | 0 / 0 / 0 |
| IngestionHttpIntegrationTest | 7 | 0 / 0 / 0 |
| IngestionServiceTest | 8 | 0 / 0 / 0 |
| MarkdownChunkerTest | 8 | 0 / 0 / 0 |
| VectorValidatorTest | 3 | 0 / 0 / 0 |
| **Всего** | **132** | **0 / 0 / 0** |

| JS-набор | Число | Падения / пропуски |
| --- | --- | --- |
| sse-parser.test.mjs | 8 | 0 / 0 |
| stream-api.test.mjs | 19 | 0 / 0 |
| chat-controller.test.mjs | 11 | 0 / 0 |
| ui.test.mjs | 7 | 0 / 0 |
| conversations.test.mjs | 15 | 0 / 0 |
| context.test.mjs | 10 | 0 / 0 |
| ingestion.test.mjs | 9 | 0 / 0 |
| **Всего** | **79** | **0 / 0** |

Основные подтверждения:

- Unicode code points без разрезанных surrogate pairs, точный overlap, номера строк, иерархия ATX и fences.
- Устойчивые IDs; изменения содержимого, title или splitter policy меняют ID, соседняя corpusVersion сама по себе — нет.
- Правильные cardinality/dimensions, finite/nonzero, defensive copies и порядок vectors.
- Реальный файл snapshot, повторное открытие, полная замена stale chunks и явный отказ на повреждённом JSON.
- Reuse без новых embed-вызовов/перезаписи, перестроение при изменении корпуса/digest/dimensions/strategy.
- Частичная ошибка batch и неверные векторы не изменяют предыдущий файл.
- Busy guard, повторная проверка digest и отклонение response другой модели.
- Настоящий Spring AI → Ollama HTTP-адаптер отправляет batch input, model, truncate=false, dimensions и num_ctx.

HTTP-тест выявил отсутствие truncate=false при коротком embed(List) в этой связке. Рабочий adapter использует явный EmbeddingRequest с OllamaEmbeddingOptions; тест подтверждает итоговый wire JSON.

## Homework

После аудита ДЗ стало отдельным приращением к готовой лекции. Весь базовый
путь lecture предоставлен реализованным; новый TODO находится только в
`IncrementalEmbeddingPlanner.java`. Новые acceptance-тесты отсутствуют в lecture и проверяют
дополнительное поведение, описанное в `homework/README.md`.

Проверено в staging-копии курса на Java 25:

- `./mvnw -o -DskipTests package`: каркас собирается.
- `./mvnw -o -Dtest='*Test,!*HomeworkAcceptanceTest' test`: **132**
  регрессионных Java-сценариев проходят без ошибок и пропусков.
- Новый acceptance-набор: **6** сценариев ожидаемо красные
  (1 assertion failure(s), 5 error(s)); причина — единственный новый TODO.
- `npm ci --offline --ignore-scripts` и `npm test`: **79 JS** проходят
  и в исходном каркасе, и во временном эталоне.
- Во временную копию подставлена только реализация `IncrementalEmbeddingPlanner.java`;
  тесты не менялись. Полный быстрый набор: **138 Java**;
  всё зелёное, пропусков нет. Решение в homework не переносилось.

Локальные HTTP-контрактные тесты выполнялись с разрешённым запуском локальных
портов: запрет открытия порта в ограниченной среде не относится к ошибкам
реализации. Реальные веса chat/embedding-моделей в этом аудите не запускались.
Новые API проверены на уровне сервисных acceptance-тестов; интерактивный
браузерный прогон новых API отдельно не проводился.

## Браузер

Упакованный Java-проект работал с локальной HTTP-заглушкой Ollama и отдельными временными SQLite/index файлами. Encoder fixture — fixture-embed:1, digest из 64 букв a, три синтетических измерения.

Проверены:

1. Preview MARKDOWN_SECTIONS 600/80: 12 чанков с path/section/lines/ID, открываемый raw code fragment и ссылка на документ.
2. Index: 12 чанков сохранены, HTTP-stub получил два batch по 8 и 4 текста с truncate=false и dimensions=3.
3. Повтор: reused, новых embed-запросов не появилось.
4. Restart отдельного тестового процесса с тем же файлом: status восстановил снимок и явно сообщил, что runtime digest ещё не проверялся.
5. Preview FIXED_WINDOW 600/80: 6 чанков, без новых embedding-вызовов.
6. JavaScript-ошибок в рабочей проверочной вкладке не обнаружено.

[Preview.jpg](Preview.jpg) показывает переиспользованный снимок и открытый чанк. Вкладка и временный процесс закрыты. Данные/векторы синтетические; это проверка продукта и протоколов, не качества encoder.

## Границы

Настоящие embedding/chat модели не запускались, веса не скачивались. Профиль по умолчанию рассчитан на embeddinggemma:300m/768; фактический encoder проверяется вручную по лекции. Числа 12/6 относятся к текущему corpus/chunker, а не retrieval-метрикам.

Chunker — ограниченный ATX/fence parser с окнами по code points, не полный CommonMark и не точный tokenizer. Snapshot ограничен 512 чанками и 16 MiB. ATOMIC_MOVE проверен на macOS; это атомарная видимость, не power-loss durability. Один процесс владеет файлом; распределённой блокировки и resumable job нет.

Двойная проверка digest не является транзакцией с runtime; модель не должна обновляться параллельно. Status не проверяет актуальный digest. Схема JSON не подписана и не служит форматом приёма недоверенных индексов.

Чат использует прежние CHAT/FULL_CONTEXT. Retrieval, pgvector и первый RAG — следующий урок. Бланк сравнения не заполнен выдуманными результатами модели.

## Повторить

Из lecture с JDK 25 и Node 24:

```bash
./mvnw package
npm ci
npm test
```

После реализации TODO — те же команды из homework. Автоматическим проверкам реальные веса не нужны.
