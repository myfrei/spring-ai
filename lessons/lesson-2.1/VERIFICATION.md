# Проверка урока 2.1

Дата: 2026-10-25.

## Среда

macOS aarch64, Temurin 25.0.5+8, Maven 3.9.11 / Wrapper 3.3.4. Boot 4.1.2, Spring AI 2.0.2, SQLite JDBC 3.53.4.0. Node.js 24.16.0, npm 11.13.0, jsdom 27.4.0. Новых внешних зависимостей нет; системная Java не переключалась.

## Lecture

Успешно выполнены ./mvnw package, npm ci и npm test. Создан исполняемый JAR, содержащий manifest и все три Markdown-ресурса корпуса.

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
| **Всего** | **102** | **0 / 0 / 0** |

| JS-набор | Число | Падения / пропуски |
| --- | --- | --- |
| sse-parser.test.mjs | 8 | 0 / 0 |
| stream-api.test.mjs | 19 | 0 / 0 |
| chat-controller.test.mjs | 11 | 0 / 0 |
| ui.test.mjs | 7 | 0 / 0 |
| conversations.test.mjs | 15 | 0 / 0 |
| context.test.mjs | 10 | 0 / 0 |
| **Всего** | **70** | **0 / 0** |

Подтверждены:

- Устойчивая версия, нормализация переводов строк, изменение hash/version после изменения содержимого или metadata.
- Allowlist путей, строгий UTF-8, лимиты загрузки, immutable snapshot и lookup по ID.
- CHAT без документов и FULL_CONTEXT со всем корпусом, system отдельно от user.
- Обязательная часть не обрезается; история выбирается свежим непрерывным суффиксом целых пар.
- Граница бюджета по estimator и отказ при overflow без обращения к provider.
- Preview без turn/LLM; 413 до SSE и failed/CONTEXT_TOO_LARGE внутри HTTP 200 для stream.
- Синхронная ошибка сборщика сохраняется как failed, а не cancelled.
- Настоящий WebClient отправляет документы/metadata, num_ctx=4096 и num_predict=256; SQLite хранит исходный вопрос без дублирования корпуса.
- UI обрабатывает context до delta, валидирует metadata, блокирует mode во время запроса и показывает безопасные ссылки.

## Homework

После аудита ДЗ стало отдельным приращением к готовой лекции. Весь базовый
путь lecture предоставлен реализованным; новый TODO находится только в
`PinnedContextAssembler.java`. Новые acceptance-тесты отсутствуют в lecture и проверяют
дополнительное поведение, описанное в `homework/README.md`.

Проверено в staging-копии курса на Java 25:

- `./mvnw -o -DskipTests package`: каркас собирается.
- `./mvnw -o -Dtest='*Test,!*HomeworkAcceptanceTest' test`: **102**
  регрессионных Java-сценариев проходят без ошибок и пропусков.
- Новый acceptance-набор: **5** сценариев ожидаемо красные
  (2 assertion failure(s), 3 error(s)); причина — единственный новый TODO.
- `npm ci --offline --ignore-scripts` и `npm test`: **70 JS** проходят
  и в исходном каркасе, и во временном эталоне.
- Во временную копию подставлена только реализация `PinnedContextAssembler.java`;
  тесты не менялись. Полный быстрый набор: **107 Java**;
  всё зелёное, пропусков нет. Решение в homework не переносилось.

Локальные HTTP-контрактные тесты выполнялись с разрешённым запуском локальных
портов: запрет открытия порта в ограниченной среде не относится к ошибкам
реализации. Реальные веса chat/embedding-моделей в этом аудите не запускались.
Новые API проверены на уровне сервисных acceptance-тестов; интерактивный
браузерный прогон новых API отдельно не проводился.

## Браузер

Упакованное приложение запущено с локальным HTTP-stub Ollama и отдельной временной SQLite. Созданы два независимых диалога для одинакового вопроса о порте TaskShelf:

- CHAT: отчёт без источников, оценка 128 / 3584; outgoing request без корпуса.
- FULL_CONTEXT: три источника overview/runbook/runtime, версия корпуса, оценка 1630 / 3584; outgoing request с корпусом.
- Отчёт пришёл до завершения ответа; select и переключение диалогов были заблокированы во время генерации.
- Список содержит ссылки на разрешённые маршруты документов; HTTP-тест подтвердил text/plain для документа и 404 для неизвестного ID.
- В проверочной вкладке JavaScript-ошибок не обнаружено.

[Preview.jpg](Preview.jpg) показывает FULL_CONTEXT с синтетическим ответом, явно помеченным [HTTP-стенд]. Это проверка интерфейса и доставки, не измерение качества LLM. Вкладка и временный процесс закрыты после проверки.

## Границы результата

Веса настоящей LLM не запускались и не скачивались. Бланк сравнения не заполнен выдуманными ответами. Качество, реальная длина prompt, runtime cache и время inference требуют ручного прогона.

Estimator — эвристика, не точный tokenizer и не гарантированная верхняя граница. Проверено соблюдение прикладной политики по этой оценке. cacheEvidence=NOT_MEASURED; реализация CAG/KV-cache не заявляется. Sources означают приложенные документы, а не подтверждённые цитаты.

Snapshot корпуса загружается при старте. Отчёт контекста не сохраняется в SQLite; история 1.4 сохраняется. Для воспроизводимости бенчмарка нужно сохранить report, версию корпуса, model/options и независимые conversationId. RAG, chunks и embeddings ещё не реализованы.

## Повторить

Из lecture с JDK 25 и Node.js 24:

```bash
./mvnw package
npm ci
npm test
```

Те же команды выполняются из homework после реализации TODO. Автоматическим проверкам реальные веса не нужны.
