# Урок 3.3. Контекстные запросы, MultiQuery и RRF

- [Полная пошаговая лекция и рабочий проект](lecture/README.md).
- [Worksheet эксперимента](lecture/evaluation/Query-Experiment.md).
- [Самостоятельное ДЗ: TTL/LRU-кеш query plans](homework/README.md).
- [Проверка материалов и фактические результаты](VERIFICATION.md).
- [Предыдущий урок 3.2](../lesson-3.2/README.md).
- [Следующий урок 3.4](../lesson-3.4/README.md).

RepoPilot получает отдельный query API: contextual compression, ограниченное расширение формулировок, до трёх searches и RRF перед прежним budget planner/advisor. Исходный вопрос остаётся вопросом для финальной модели. RRF score и cosine показаны отдельно.

Стек: Java 25, Boot 4.1.2, Spring AI 2.0.2, Ollama, PostgreSQL 17/pgvector 0.8.1. UI диагностики унаследован; новый эксперимент выполняется JSON API, отдельной панели нет. Каждая папка lecture/homework — самостоятельный Maven-проект.

Из lecture, с подготовленными Ollama chat/embedding моделями:

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

Затем создайте ingestion artifact, опубликуйте его в pgvector и выполните /api/queries/plan, /prepare, /call или /compare по командам лекции. Для автоматических проверок:

```bash
./mvnw test
npm ci
npm test
./mvnw verify -Ppgvector-it
```

При подготовке lecture прошли **223 быстрых Java + 24 pgvector IT + 104 JS**. Полный профиль требует Docker; реальные веса не нужны контрактным fixtures. Качество настоящих моделей оценивается отдельным экспериментом с учётом дополнительных calls, retrieval и полной задержки.

В homework query planner и RRF полностью готовы. Новая задача — TTL/LRU-кеш подготовленных query plans с корректной identity и контролируемым временем. Условия и отдельные acceptance описаны в его README; фактические результаты каркаса/эталона — в VERIFICATION.
