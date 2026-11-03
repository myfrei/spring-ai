# Урок 3.4. Reranking найденных chunks

- [Полная пошаговая лекция и рабочий пример](lecture/README.md).
- [Пустой бланк эксперимента](lecture/evaluation/Reranking-Experiment.md).
- [Самостоятельное ДЗ: offline-метрики ранжирования](homework/README.md).
- [Фактические результаты проверки](VERIFICATION.md).
- [Предыдущий урок 3.3](../lesson-3.3/README.md).
- [Следующий урок4.1](../lesson-4.1/README.md).

RepoPilot получает NONE / LEXICAL / LLM reranking после retrieval и RRF. LEXICAL — текстовая эвристика, LLM — ограниченное listwise-ранжирование chat-моделью. Отдельный cross-encoder в этом срезе не используется. Финальная модель получает только отобранные документы и исходный вопрос, проверенные отдельным RagPlanner.

Стек: Java 25, Spring Boot 4.1.2, Spring AI 2.0.2, Ollama, PostgreSQL 17 / pgvector 0.8.1. Новый сценарий работает через JSON API `/api/reranking/prepare`, `/call`, `/compare`; UI унаследован, отдельной панели нет. Каждый lecture/homework — самостоятельный Maven-проект.

Из lecture, после подготовки Ollama chat/embedding моделей:

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

Затем выполните ingestion/index и rag/publish по лекции. Для автоматических проверок:

```bash
./mvnw test
npm ci
npm test
./mvnw verify -Ppgvector-it
```

Полный профиль требует Docker. Fixtures проверяют контракт без настоящих весов; качество и задержку модели исследуют отдельно. Счётчики и границы проверки приведены в VERIFICATION.

В homework база reranking готова. Новая задача — offline nDCG@K, RR@K и recall@K по явной разметке ранжирования; она не требует повторять реализацию reranker или обращаться к LLM/SQL.
