# Урок 2.4. Сравнение CHAT, FULL_CONTEXT и RAG

- [Полная Markdown-лекция и рабочий пример](lecture/README.md).
- [Самостоятельное ДЗ: парный отчёт регрессий](homework/README.md).
- [Результаты технической проверки](VERIFICATION.md).
- [Бланк выбора стратегии](lecture/evaluation/Strategy-Decision.md).
- [Dataset: 20 DEV и 10 HOLDOUT](lecture/src/main/resources/evaluation/cases.json).
- [Снимок панели сравнения](Preview.jpg) — синтетический HTTP-стенд.
- [Предыдущий урок 2.3](../lesson-2.3/README.md).

Одинаковые вопросы, пустая история, фиксируемые условия и ротация режимов. Сохраняются raw answers/sources, задержки, provider usage, ошибки и ручные оценки; отчёт экспортируется в Markdown. Warm-up, NO_EVIDENCE и неудачные ответы учитываются отдельно.

Java 25, Boot 4.1.2, Spring AI 2.0.2. Lecture и homework — самостоятельные проекты. Новых зависимостей относительно 2.3 нет.

Из lecture:

```bash
./mvnw test
npm ci
npm test
./mvnw verify -Ppgvector-it
docker compose up -d
./mvnw spring-boot:run
```

Для реального inference подготовьте Ollama с embeddinggemma:300m и qwen2.5:3b. Сначала индексируйте документы и опубликуйте в pgvector. Затем в панели сравнения начните с dev-01,dev-02. Во время benchmark не отправляйте другие запросы Ollama.

В lecture проходят **171 Java unit/HTTP + 13 pgvector integration + 96 JavaScript**. Веса настоящей LLM не загружались: проверены Spring AI, реальный SQL и UI с HTTP-стендом. Результаты качества оставлены для настоящего эксперимента, победитель заранее не задан.

Домашнее задание переработано: Лекция показывает сводные оценки по режимам. ДЗ сравнивает два прогона по одному caseId и repetition: рост среднего не должен скрыть ухудшение конкретного ответа или цитаты. Готовая база lecture предоставлена в homework; новые acceptance проверяют новое поведение, а не повторяют упражнения лекции. См. [условия](homework/README.md).
