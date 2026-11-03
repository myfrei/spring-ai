# Урок 2.2. Документы, чанки и embeddings

- [Полная Markdown-лекция и рабочий пример](lecture/README.md).
- [ДЗ: инкрементальное переиспользование embeddings](homework/README.md).
- [Результаты проверки](VERIFICATION.md).
- [Бланк сравнения chunking](lecture/evaluation/Chunking-Comparison.md).
- [Панель подготовки индекса](Preview.jpg) — HTTP-стенд, синтетические векторы.
- [Предыдущий урок 2.1](../lesson-2.1/README.md).

Добавлены FIXED_WINDOW/MARKDOWN_SECTIONS, чанки с source/lines/hash/ID, отдельная embedding-модель, batches и атомарный JSON-снимок. Fingerprint учитывает корпус, splitter, model tag/digest, dimensions и формат входа. Повтор переиспользует готовый снимок, ошибка не публикует частичный результат.

Java 25, Boot 4.1.2, Spring AI 2.0.2, SQLite JDBC 3.53.4.0; Node 24, jsdom 27.4.0. Lecture и homework — самостоятельные Maven-проекты. Внешние зависимости сохранены из 2.1.

Из lecture:

```bash
./mvnw package
npm ci
npm test
./mvnw spring-boot:run
```

Откройте http://127.0.0.1:8080/. Предпросмотр не требует работающей embedding-модели. Для настоящей индексации подготовьте embeddinggemma:300m в Ollama; профиль по умолчанию — 768 измерений. Автозагрузки весов нет.

В lecture проходят **132 Java- и 79 JavaScript-проверок**. Homework компилируется, но часть проверок намеренно красная; неизменённые тесты проверены на временном решении.

Индекс лежит в ./data/ingestion/index.json относительно рабочей директории, история чата — отдельно в SQLite. Поиск ещё не подключён. Следующий урок 2.3 — pgvector и первый RAG.

Домашнее задание переработано: Лекция повторно использует только целый неизменённый снимок. ДЗ переиспользует отдельные векторы неизменённых encoder inputs, когда корпус или splitter изменились, и пересчитывает только отсутствующие входы. Готовая база lecture предоставлена в homework; новые acceptance проверяют новое поведение, а не повторяют упражнения лекции. См. [условия](homework/README.md).
