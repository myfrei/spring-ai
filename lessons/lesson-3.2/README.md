# Урок 3.2. ChatClientRequest и собственный Advisor

- [Полная лекция и рабочий пример](lecture/README.md).
- [Новое ДЗ: тематические профили источников](homework/README.md).
- [Проверка и границы результата](VERIFICATION.md).
- [Эксперимент с порядком advisors](lecture/evaluation/Advisor-Order.md).

В лекции: snapshot/service RAG advisor, call/stream, metadata, порядок, guard двойного контекста. Бюджет и старый UI сохранены. Новая точка `POST /api/advisors/call` выполняет синхронный RAG без истории.

В ДЗ готова вся lecture-база; студент реализует дополнительную server-owned DocumentScope policy и новый `/api/advisors/scoped/call`. Это отдельная функция, а не повтор написания advisors лекции.

Java 25 / Boot 4.1.2 / Spring AI 2.0.2. Из lecture:

```bash
./mvnw test
npm ci
npm test
./mvnw verify -Ppgvector-it
```

Для настоящего RAG нужны Ollama и опубликованный pgvector-снимок; подробные команды в лекции.
