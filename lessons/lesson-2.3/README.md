# Урок 2.3. Документы, чанки и embeddings: первый RAG

Продолжаем pipeline 2.2: PostgreSQL/pgvector, поиск, бюджет и ответ со ссылками на чанки. Тема подготовки документов уже реализована в [2.2](../lesson-2.2/README.md); здесь готовые embeddings становятся постоянным поисковым индексом.

- [Полная лекция в Markdown и рабочий пример](lecture/README.md).
- [Самостоятельное ДЗ: разнообразие источников перед сборкой rag](homework/README.md).
- [Результаты проверки](VERIFICATION.md).
- [Бланк retrieval-эксперимента](lecture/evaluation/Retrieval-Experiment.md).
- [Снимок интерфейса: RAG и источники](Preview.jpg) — синтетический HTTP-стенд.
- [Предыдущий урок](../lesson-2.2/README.md).

Java 25, Spring Boot 4.1.2, Spring AI 2.0.2. Lecture и homework — отдельные Maven-проекты. Для SQL-интеграции нужен Docker; для настоящего inference — Ollama с chat- и embedding-моделью.

Из lecture:

```bash
./mvnw test
npm ci
npm test
./mvnw verify -Ppgvector-it
docker compose up -d
./mvnw spring-boot:run
```

В UI сначала создайте embeddings, затем опубликуйте в pgvector. Откройте новый диалог и выберите RAG. По умолчанию embeddinggemma:300m/768, qwen2.5:3b, Pg на 5433 и UI на 8080. Модели загружаются явной командой из лекции.

Проверено: **142 Java unit/HTTP + 11 pgvector integration + 86 JavaScript**. Настоящая база и Spring AI проверены с локальным HTTP-stub; качество настоящей LLM/encoder пока не измерено. Домашний каркас компилируется, новые проверки ожидаемо красные.

Сохранены история SQLite, CHAT/FULL_CONTEXT, streaming, отмена и повтор. Новые источники относятся к фактически переданным чанкам; корректность утверждений и цитат модели всё равно проверяется отдельно.

Домашнее задание переработано: Лекция передаёт ранжированный префикс retrieval. ДЗ удаляет точные и перекрывающиеся повторы, ограничивает монополию одного документа и перенумеровывает фактически выбранные sources до применения бюджета. Готовая база lecture предоставлена в homework; новые acceptance проверяют новое поведение, а не повторяют упражнения лекции. См. [условия](homework/README.md).
