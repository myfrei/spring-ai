# Проверка урока 3.4

Дата: **2026-10-31**. Проверен самостоятельный проект на Java 25 / Spring Boot 4.1.2 / Spring AI 2.0.2. Реализация развивается из полного среза3.3, дополнительных библиотек для reranker не добавлено.

## Рабочий пример lecture

```bash
cd lecture
./mvnw verify -Ppgvector-it
npm ci
npm test
```

Результат: **273 Java/HTTP +35 pgvector IT +104 JavaScript**, без failures/errors/skips. Docker нужен для профиля pgvector-it. Maven/JS-проверки в проверочной среде использовали уже загруженные зависимости с offline-флагом.

Среда: Eclipse Temurin **25.0.5+8**, Maven Wrapper **3.9.11**, Node **24.16.0**, npm **11.13.0**, Docker Desktop с server **29.4.0**. Официальный архив JDK загружен из Eclipse Adoptium, SHA256 сверена. SQL-тесты использовали настоящий образ `pgvector/pgvector:0.8.1-pg17` и Testcontainers **2.0.5**; модель была локальным HTTP-fixture, не настоящими весами.

### Новые быстрые проверки

К исходным223 добавлено **50**:

| Набор | Количество | Проверяемое свойство |
| --- | ---: | --- |
| StrictRerankScoresTest | 20 | Полный набор IDs, дубликаты JSON keys/IDs, диапазон и тип score, extra fields, trailing output |
| RerankConfigurationAndRequestTest | 10 | Зависимые flags, strict integer keep и границы DTO |
| RerankServiceTest | 11 | NONE/LEXICAL/LLM, tie-break, неизменяемость, fallback, budget0, пустая выдача, post-error digest/availability |
| SpringRerankModelTest | 3 | Реальные SDK messages/options, совпадение измеренного и отправленного prompt, граница бюджета |
| RerankPipelineTest | 6 | Reranking до бюджета, исходный вопрос, единственный retrieval/finalcall, NO_EVIDENCE, состав публичного отчёта |

Тест provider options использует fixture того же типа, что настоящий OllamaChatModel: Spring AI2.0.2 объединяет options через ChatModel.getOptions(). Начальный generic fixture возвращал DefaultChatOptions и не подходил для проверки Ollama-specific caps. Fixture исправлен; assertions не ослаблялись. Отдельный настоящий HTTP-путь подтверждает temperature0, num_ctx4096 и num_predict768.

### Новые интеграционные проверки

К24 унаследованным IT добавлено **11**. Они проходят через настоящий Spring MVC, Spring AI OllamaChatModel/ChatClient, HTTP-fixture и SQL pgvector:

- Listwise scoring переставляет реальные найденные IDs до keep/финального бюджета; original question сохраняется. Один query embedding/search, один scoring и один finalcall.
- NONE/LEXICAL не вызывают scoring LLM.
- Некорректный JSON, незавершённая генерация списка и HTTP500 дают предсказуемый baseline fallback, по одной попытке без repair/retry.
- Переполненный scoring prompt и пустая выдача не вызывают scoring; при NO_EVIDENCE нет финальной генерации.
- Смена digest после невалидного scoring прерывает выполнение вместо маскировки fallback.
- Сравнение использует один общий SQL snapshot и учитывает его стоимость один раз. Ветки не выполняют повторный поиск.
- Изменение активного индекса или chat-модели между ветками отвергает пару.
- Неверные keep/strategy/split и отсутствие индекса возвращают публичные коды; незавершённый final response не объявляется COMPLETED.

Фактический HTTP JSON проверяет provider caps. Usage scoring и финального ответа сверяется раздельно. Public preparation report не сериализует raw candidate text, Spring AI Message/Document или свободный scoring JSON; query plan при этом содержит пользовательский текст, а /call — ответ. Это не обезличенный экспорт.

## Каркас homework

Полная рабочая база lecture включена. Новое приращение — **offline evaluator** nDCG@K, RR@K и recall@K одного metadata-наблюдения. Восемь acceptance проверяют новую математику и контракт, а не повторную реализацию reranker.

```bash
cd homework
./mvnw -DskipTests package
./mvnw '-Dtest=*Test,!RankingMetricsAcceptanceTest' test
npm ci
npm test
./mvnw -Dtest=RankingMetricsAcceptanceTest test
```

Каркас компилируется. Готовая база прошла **273 Java +35 Docker IT +104 JS** при исключении RankingMetricsAcceptanceTest. Новые **8 acceptance намеренно красные**: восемь UnsupportedOperationException из единственного нового TODO.

Во временной копии заполнен только RankingMetrics.evaluate; тестовые исходники не менялись и сверены побайтово. Эталон прошёл **281 Java +35 Docker IT +104 JS**. Готовое решение не включено в homework. Восемь критериев включают graded gain, исходный rank, cutoff при gold>K, повторные document IDs, пустую выдачу/NO_GOLD, immutable input и строгие HTTP types.

Вход — ordered document IDs по исходным chunk slots и grades0..3. Повтор document ID занимает позицию, но не получает gain повторно; cutoff применяется до дедупликации. IDCG ограничен K даже при большем числе gold; отдельный случай gold>K проверен. Пустая выдача при наличии gold даёт нули, NO_GOLD — пять null. RR считается на одном наблюдении; MRR для серии — среднее RR по SCORED с отдельным числом NO_GOLD.

Дополнительно запущен собранный JAR временного эталона с отключённым RAG и недоступными Ollama/PostgreSQL: API вернул корректные SCORED, NO_GOLD (пять null) и sanitized400. Marker входного payload не попал в журнал этого сценария; тестовый процесс остановлен.

Provided HTTP adapter отвергает fractional/string K/grades, null grade и extra fields, возвращая sanitized400. Новая функция не вызывает SQL/модель и не получает вопрос, ответ или chunk content. Временная reference-реализация находится вне курса; в homework остаётся один TODO.

## Границы результата

**Реальные веса Ollama не запускались.** Fixtures подтверждают orchestration/контракты, а не качество выбранной qwen2.5 или embeddings, реальную latency и аппаратный минимум. Для этого заполняется [Reranking-Experiment.md](lecture/evaluation/Reranking-Experiment.md).

LEXICAL — учебный token-overlap, не семантическая модель. LLM listwise scoring не является cross-encoder; score не означает cosine или вероятность. Структурно верный JSON может содержать плохие оценки. Scorer использует исходный вопрос и candidate text; history участвует в retrieval/final answer, но не передаётся reranker отдельно.

Бюджет оценивается эвристикой Utf8TokenEstimator по полному scoring prompt. Reserve768 и safety256 — политика приложения, не точный tokenizer. Указанный HTTP read-timeout120s не является общим deadline всех этапов. Встроенный /compare имеет фиксированный порядок и один общий snapshot: это диагностическая пара, не статистический benchmark.

Новый API не создаёт автоматически benchmark run/trace и не пишет реплики в SQLite conversation. Домашние document-level метрики — объявленная учебная конвенция; они не доказывают наличие нужного факта в конкретном chunk и не заменяют проверку ответа/цитат.
