# Проверка урока 3.3

Дата: 2026-10-26. Temurin 25.0.5+8; Spring Boot 4.1.2; Spring AI 2.0.2; Node 24.16.0. Дополнительная зависимость — `spring-ai-rag:2.0.2`; API и формат transformers сверены с source JAR этого релиза.

## Рабочий пример

```bash
cd lecture
./mvnw verify -Ppgvector-it
npm ci
npm test
```

Итог: **223 быстрых Java/HTTP +24 pgvector IT +104 JavaScript**, без ошибок и пропусков. Требуется Docker для IT. Ollama в автоматических проверках представлен локальным HTTP-fixture; настоящие веса не загружаются.

Новые unit-проверки: **13 QueryPlannerTest +5 ReciprocalRankFusionTest +4 SpringQueryModelBudgetTest +1 QueryFeatureConfigurationTest**. Они проверяют original-first, историю, максимум два подготовительных вызова, максимум три уникальных поиска, trim/dedup, fallback без retry, смену digest, неизменяемость результата, валидацию, RRF support, стабильный tie-break, отдельный cosine и несовместимость snapshots.

Шесть дополнительных IT проходят через настоящий Spring MVC, Spring AI CompressionQueryTransformer/MultiQueryExpander и PostgreSQL/pgvector:

1. Контекстный запрос + expansion: два подготовительных вызова, три query embeddings/SQL-поиска, один финальный ответ. Фактический prompt начинается исходным вопросом и содержит бюджетные источники.
2. Некорректное число строк expansion: прямой поиск, один уже потраченный подготовительный вызов отражён в отчёте.
3. DIRECT plan без индекса и отказ NO_EVIDENCE без финального вызова chat.
4. Явный DEV/caseId, фиксированный порядок DIRECT/EXPANDED, отдельная стоимость и identity в сравнении.
5. Публичный HTTP-контракт: 400 INVALID_REQUEST и 409 INDEX_NOT_READY.
6. Валидная по DTO, но слишком большая история: подготовительный prompt отклонён до inference, в отчёте ноль подготовительных/model-вызовов и один прямой поиск.

Остальные 18 IT наследуют RAG/Advisors/диагностику, включая точную проекцию бюджета и отсутствие повторного retrieval на каждый delta. UI унаследован из 3.1–3.2, новой query-панели нет; новый сценарий проверяется JSON API.

Первый прогон нового IT нашёл ошибку ожидаемой строки теста: реальный шаблон начинается `Вопрос: …`. Ожидание исправлено по действительному RagPrompt; финальный полный прогон зелёный. В ходе проверки QueryController добавлен в exception advice, чтобы новые маршруты возвращали стабильный code вместо общего500.

## Каркас домашнего задания

Полная рабочая база lecture предоставлена; новое приращение — ограниченный TTL/LRU-кеш **планов подготовки запросов**. Единственный TODO: QueryPlanCache.getOrCompute. Контроллер, key factory, cacheability, Clock и регрессии готовы.

```bash
cd homework
./mvnw -DskipTests package
./mvnw '-Dtest=*Test,!QueryPlanCacheAcceptanceTest' test
npm ci
npm test
./mvnw -Dtest=QueryPlanCacheAcceptanceTest test
```

Каркас компилируется; **223 базовых Java +104 JS** проходят. Новые **9 acceptance** красные из-за явного TODO: две assertion failures и семь errors. Проверяются hit, текущая стоимость, inclusive TTL от окончания вычисления, LRU/capacity, изоляция истории/режима/prompt/digest, обход DIRECT/fallback, ошибка supplier, смена runtime identity и конкурентность.

Перед финальным прогоном независимое ревью выявило и исправило три пограничных случая: query beans/controller теперь требуют также включённый RAG; реальный подготовительный prompt проверяется по бюджету `window−128−256` до счётчика вызовов; digest проверяется и после неудачного ответа/исключения. Добавлены восемь unit-регрессий и один HTTP/SQL-тест. Оценка токенов эвристическая, как в предыдущих уроках; guard не является точным tokenizer модели.

Во временном эталоне заменён только один TODO-файл; тестовые исходники остались идентичны. Полный результат: **232 Java +24 Docker IT +104 JS**, всё зелёное. Эталон не включён в homework.

## Границы проверки

Fixtures доказывают orchestration и контракты, а не полезность переформулировок настоящей модели. Качество embeddings/ответов, реальные задержки и аппаратный минимум требуют отдельного эксперимента по [Query-Experiment.md](lecture/evaluation/Query-Experiment.md).

RRF объединяет ранги, cosine хранится отдельно; reranker этим не реализован. `/compare` даёт одну пару с фиксированным порядком и защищает identity индекса/chat-модели; это не статистический benchmark и не автоматическая ручная оценка. Запросы этого API не сохраняются как evaluation run/trace.

В кеше ДЗ нет retrieval-результатов, готовых ответов или KV-кеша. Plan.preparationCalls — историческая стоимость создания значения; incrementalPreparationCalls отражает новые вызовы текущего обращения, на hit равно0.
