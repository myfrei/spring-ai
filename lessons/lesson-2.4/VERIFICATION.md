# Проверка урока 2.4

Дата: **2026-10-25**, местная дата пользователя. Проверены исполнение сравнения и честность учёта метрик. Семантическое качество настоящих весов не измерялось.

## Среда

- macOS arm64, Temurin JDK 25.0.5+8 выбран отдельно от системной Java.
- Maven Wrapper 3.3.4 / Maven 3.9.11, Spring Boot 4.1.2, Spring AI 2.0.2.
- PostgreSQL JDBC 42.7.14, SQLite JDBC 3.53.4.0.
- Node 24.16.0, npm 11.13.0, jsdom 27.4.0.
- Testcontainers 2.0.5; Docker с работающим Compose.
- Образ pgvector/pgvector:0.8.1-pg17. В Compose-прогоне — PostgreSQL 17.8, vector 0.8.1, arm64.
- Runtime Ollama API в проверках заменён локальным HTTP-стендом. Digest вида aaaa/bbbb/cccc и трёхмерные векторы — синтетические fixtures.

## Рабочий пример

| Проверка | Результат |
| --- | --- |
| ./mvnw test | 171 сценарий, 0 failures/errors/skipped |
| ./mvnw verify -Ppgvector-it | 171 быстрый + 13 pgvector integration, все зелёные |
| ./mvnw -DskipTests package | Исполняемый JAR собран |
| npm ci и npm test | 96 JavaScript-проверок, все зелёные |
| Compose up -d --wait на отдельном порту | Контейнер Healthy |
| Browser smoke | Ingestion, publish, 6 measured + 3 warm-up, ручная оценка, восстановление отчёта |

Итого: **184 Java + 96 JavaScript = 280**. Пропусков нет. По сравнению с 2.3 добавлены 29 unit/HTTP Java, 2 pgvector integration и 10 JavaScript.

### Быстрые проверки

- EvaluationDatasetTest — 4: 20 DEV/10 HOLDOUT, version hash, категории и длины, запрет cross-split IDs, OR/AND evidence, document identity и UNKNOWN=null.
- RunScheduleTest — 4: все комбинации без дублей, ротация режимов, выбранные modes/bounds, стабильный prompt hash с учётом ролей/порядка.
- CaseMeasurementTest — 3: retrieval/preparation/generation и оба TTFT; пробельная delta; null без модели; provider usage отдельно от оценки.
- ComparisonSummaryTest — 4: ошибки/NO_EVIDENCE вне latency, warm-up вне сводки, явные judged/n, median/nearest-rank p95, raw failures в Markdown.
- RunStoreTest — 3: повторное чтение другим экземпляром, атомарный файл, INTERRUPTED без выдуманного completion, missing/corrupt errors.
- ExperimentCoordinatorTest — 6: отдельный DEV warm-up перед HOLDOUT, один активный job, cancel без запуска хвоста, смена условий, failed warm-up, manual judgment/server timestamp, невозможность проверить условия после ответа.
- EvaluationHttpIntegrationTest — 5: настоящий Spring AI HTTP payload/streaming/options/usage, пустая история, отсутствие answer key, hash фактического prompt, export и ручная оценка, OUTPUT_LIMIT, смена digest, async busy/cancel, split validation.

Число кейсов в строке описания не означает отдельный JUnit test на каждый assert; фактические количества указаны рядом с классами.

### Настоящий pgvector

Сохранены 11 SQL-проверок 2.3 и добавлены 2:

1. Весь comparison job проходит CHAT, FULL_CONTEXT и RAG через настоящую БД, encoder HTTP API, PgVectorStore и QuestionAnswerAdvisor. Warm-up отдельно; измеряемые исходы и источники соответствуют режимам. Document embeddings повторно не пересчитываются.
2. Пустой retrieval в benchmark не вызывает chat LLM, не получает TTFT=0 и не входит в successful latency distribution.

БД Testcontainers отдельная, порт случайный. Compose-база студента не очищается тестами. Проверочный Compose-проект для браузера также отдельный.

### JavaScript и браузер

10 новых JS-проверок покрывают literal text, отсутствие автоматической оценки, null TTFT, замену строк, block/poll/cancel, обязательный ручной выбор оценок, переключение split, late restore race, восстановление последнего отчёта, HTTP methods/ошибки.

В браузере выполнен следующий путь:

1. Сформирован embeddings-снимок из 12 чанков.
2. Снимок опубликован в настоящую Compose-БД.
3. Запущены dev-01/dev-02 во всех трёх режимах: 6 measured results и 3 warm-ups, все terminal.
4. В таблице отсутствующие ручные оценки показаны «—».
5. Прочитан первый CHAT-ответ HTTP-стенда и сохранена тестовая ручная оценка с явной заметкой, что настоящий inference не выполнялся. Оценка появилась в сводке, остальные строки остались неоценёнными.
6. После перезапуска JAR восстановились те же run ID, results и Judgment; автоматически новый benchmark не запускался.
7. Ссылка Markdown-экспорта доступна; содержимое экспорта и сохранность ручной заметки проверены HTTP-интеграцией.

[Снимок панели](Preview.jpg) содержит синтетический HTTP-прогон. Его задержки характеризуют этот контрактный стенд, а не скорость реальных весов. Ручная оценка fixture не переносится в вывод о качестве модели.

## Homework

После аудита ДЗ стало отдельным приращением к готовой лекции. Весь базовый
путь lecture предоставлен реализованным; новый TODO находится только в
`PairedRunComparator.java`. Новые acceptance-тесты отсутствуют в lecture и проверяют
дополнительное поведение, описанное в `homework/README.md`.

Проверено в staging-копии курса на Java 25:

- `./mvnw -o -DskipTests package`: каркас собирается.
- `./mvnw -o -Dtest='*Test,!*HomeworkAcceptanceTest' test`: **171**
  регрессионных Java-сценариев проходят без ошибок и пропусков.
- Новый acceptance-набор: **6** сценариев ожидаемо красные
  (1 assertion failure(s), 5 error(s)); причина — единственный новый TODO.
- `npm ci --offline --ignore-scripts` и `npm test`: **96 JS** проходят
  и в исходном каркасе, и во временном эталоне.
- Во временную копию подставлена только реализация `PairedRunComparator.java`;
  тесты не менялись. Полный быстрый набор: **177 Java** + **13 IT** с настоящим PostgreSQL/pgvector в Docker;
  всё зелёное, пропусков нет. Решение в homework не переносилось.

Локальные HTTP-контрактные тесты выполнялись с разрешённым запуском локальных
портов: запрет открытия порта в ограниченной среде не относится к ошибкам
реализации. Реальные веса chat/embedding-моделей в этом аудите не запускались.
Новые API проверены на уровне сервисных acceptance-тестов; интерактивный
браузерный прогон новых API отдельно не проводился.

## Что не доказано этими проверками

- Не загружались qwen2.5:3b и embeddinggemma:300m, не оценивалось их реальное качество/память/скорость.
- Не выбран «победитель» и default strategy по результатам синтетических векторов/ответов.
- Не измерены KV-cache, reused tokens или CAG. Warm-up не доказывает cache hit.
- TTFT относится к первому непустому тексту в Java-сервисе, не к первому токену на GPU и не к отрисовке браузера.
- Все latency distributions имеют свои n и исключают неуспешные model outcomes; их нельзя сравнивать, скрыв ошибки и отказы.
- Evidence coverage — проверка конкретных excerpt/documentId, не автоматическая проверка смысла ответа и не общий recall.
- 20/10 split относится к формулировкам одного малого корпуса. HOLDOUT доступен в репозитории, дисциплина использования не обеспечивается системой скрытых тестов.
- Один процесс владеет каталогом evaluation; других клиентов Ollama и нагрузку компьютера job не блокирует.

Реальные измерения и выбор стратегии оформляются отдельно по [бланку](lecture/evaluation/Strategy-Decision.md).
