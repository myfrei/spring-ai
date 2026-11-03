# Проверка урока 2.3

Дата: 2026-10-25. Это отчёт о выполненных проверках, не заявление об измеренном качестве локальных моделей.

## Среда

- macOS arm64; JDK Temurin 25.0.5+8 выбран отдельно от системной Java.
- Maven Wrapper 3.3.4, Maven 3.9.11; Spring Boot 4.1.2, Spring AI 2.0.2.
- PostgreSQL JDBC 42.7.14, SQLite JDBC 3.53.4.0.
- Node 24.16.0, npm 11.13.0, jsdom 27.4.0.
- Docker Engine 29.4.0; Testcontainers 2.0.5.
- Образ `pgvector/pgvector:0.8.1-pg17`, arm64; фактически PostgreSQL 17.8 и vector 0.8.1.
- Полученный RepoDigest: `pgvector/pgvector@sha256:3e8b3adfd27b5707128f60956f62a793c3c9326ea8cfaf0eab7adccb5d700b21`.

Tag образа указан в Compose и Testcontainers. Digest зафиксирован здесь как результат прогона; mutable tag сам по себе не является неизменяемым snapshot всех platform manifests.

## Lecture

| Проверка | Результат |
| --- | --- |
| `./mvnw test` | 142 сценария, 0 failures/errors/skipped |
| `./mvnw verify -Ppgvector-it` | 142 быстрых + 11 SQL-интеграционных, все зелёные |
| `./mvnw -DskipTests package` | Исполняемый JAR собран |
| `npm ci` и `npm test` | 86 сценариев, все зелёные |
| `docker compose config --quiet` | Конфигурация валидна |
| Compose smoke на отдельном порту | `up -d --wait`: контейнер Healthy; проверочный проект затем остановлен |
| Browser smoke | Создание embeddings, публикация, новый диалог, RAG streaming, источник и пустая выдача |

Общий зелёный набор: **239 проверок** — 153 Java и 86 JavaScript. По сравнению с 2.2 добавлены 10 быстрых Java, 11 pgvector IT и 7 JavaScript. Одна старая UI-проверка текста обновлена под появившуюся публикацию поиска.

В одном раннем прогоне homework старый тест закрытия streaming-соединения не дождался disconnect за 3 секунды. Повтор полного набора дал только ожидаемые TODO-ошибки, а полное эталонное решение прошло этот сценарий. Это наблюдение о чувствительности сетевого smoke-теста к таймингу; тест не отключён и его ожидание не ослаблено.

### Что подтверждают новые Java-проверки

`RagContractsTest` (5): metadata/row identity, переиспользование сохранённых vectors, defensive copies, query guard, read-only selection и границы retrieval options.

`RagPlannerTest` (5): расширение prompt, буквальные фигурные скобки, бюджет ранжированного префикса, полные пары истории, NO_EVIDENCE и overflow/invalid history.

`PgVectorRagIT` (11), реальная БД в Testcontainers:

1. Идемпотентная публикация без повторного document inference.
2. Cosine score, threshold, topK и workspace в фактическом SQL.
3. Точный source и 404 после замены поколения.
4. Rollback после DELETE и неудачного INSERT, включая активный manifest.
5. Несовместимый profile и dimensions без удаления данных.
6. Пустая выдача завершает turn без chat-модели.
7. Реальный HTTP prompt после QuestionAnswerAdvisor равен бюджетной проекции; query embedding один.
8. Неверные options дают 400, неопубликованный индекс — публичный INDEX_NOT_READY.
9. Все четыре metadata-фильтра исключают подложенные чужие строки до topK.
10. Смена runtime digest во время query и stale corpus останавливают поиск.
11. Изменённый content с прежним textHash отклоняется.

Ollama заменён loopback HTTP-стендом, а не mock Java EmbeddingModel/ChatClient. В пути остаются реальные Spring AI HTTP-клиенты, PgVectorStore, advisor, PostgreSQL driver, SQL и транзакции. Синтетические vectors имеют 3 измерения; это только fixture и не размерность настоящей EmbeddingGemma.

### JavaScript и браузер

Новые JS-проверки покрывают literal text, ссылки на fingerprint/chunkId, очистку источников при NO_EVIDENCE, правдивую подпись без вызова chat-модели, проверку SSE report, маршрут RAG и публикацию с блокировкой кнопки.

Исполняемый JAR проверен в браузере на отдельном порту с временными SQLite/JSON и отдельным PostgreSQL. Получены 12 чанков, публикация активирована, ответ завершён. Ссылка S2 открыла `RUNBOOK.md:3–8` с chunkId, hash и исходным текстом про профиль local/порт 8091. Ветка `absent` вернула NO_EVIDENCE без источников и программное сообщение о пустой выдаче. После перезапуска JAR история сохранилась.

[Снимок интерфейса](Preview.jpg) и [ветка пустой выдачи](No-Evidence.jpg) используют ответ, явно помеченный «HTTP-стенд», и синтетические scores. Это подтверждение UI/интеграции, не пример измеренного качества inference.

## Homework

После аудита ДЗ стало отдельным приращением к готовой лекции. Весь базовый
путь lecture предоставлен реализованным; новый TODO находится только в
`EvidenceSelector.java`. Новые acceptance-тесты отсутствуют в lecture и проверяют
дополнительное поведение, описанное в `homework/README.md`.

Проверено в staging-копии курса на Java 25:

- `./mvnw -o -DskipTests package`: каркас собирается.
- `./mvnw -o -Dtest='*Test,!*HomeworkAcceptanceTest' test`: **142**
  регрессионных Java-сценариев проходят без ошибок и пропусков.
- Новый acceptance-набор: **5** сценариев ожидаемо красные
  (1 assertion failure(s), 4 error(s)); причина — единственный новый TODO.
- `npm ci --offline --ignore-scripts` и `npm test`: **86 JS** проходят
  и в исходном каркасе, и во временном эталоне.
- Во временную копию подставлена только реализация `EvidenceSelector.java`;
  тесты не менялись. Полный быстрый набор: **147 Java** + **11 IT** с настоящим PostgreSQL/pgvector в Docker;
  всё зелёное, пропусков нет. Решение в homework не переносилось.

Локальные HTTP-контрактные тесты выполнялись с разрешённым запуском локальных
портов: запрет открытия порта в ограниченной среде не относится к ошибкам
реализации. Реальные веса chat/embedding-моделей в этом аудите не запускались.
Новые API проверены на уровне сервисных acceptance-тестов; интерактивный
браузерный прогон новых API отдельно не проводился.

## Что не проверено

Настоящие веса qwen2.5:3b и embeddinggemma:300m не скачивались. Не измерены русский retrieval, answerability, правдивость цитат, выбор threshold, latency/TTFT и потребление памяти на машине студента. Не заявляется cache/CAG, ANN/HNSW performance, поддержка множества пользователей или полный production-профиль БД.

Такие измерения выполняются отдельно по [бланку](lecture/evaluation/Retrieval-Experiment.md). Открываемый source доказывает соответствие ссылки чанку; он не доказывает, что каждое утверждение модели следует из этого чанка. Старые поколения и долговременный provenance в этом срезе не хранятся.
