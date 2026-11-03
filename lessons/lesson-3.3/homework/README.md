# ДЗ 3.3. Ограниченный cache планов подготовки запроса

[Материалы урока](../README.md) · [Лекция](../lecture/README.md) · [Приёмочные тесты](src/test/java/dev/myfrei/repopilot/cache/QueryPlanCacheAcceptanceTest.java) · [Отчёт по ДЗ](SUBMISSION.md)

Используйте **готовые** contextual rewrite, query expansion, ограничение вариантов, RRF и RAG-путь из лекции. Добавьте локальный TTL/LRU cache для `QueryPlanner.Plan`, чтобы избежать повторной подготовки одного и того же запроса. Реализовать предстоит именно кеширование.

Кэшируем только результат преобразования вопроса. Поиск в pgvector, найденные chunks, готовый RAG-план и ответы модели не сохраняются в этом cache. Это отдельное приращение продукта, а не повторение rewrite/expansion из лекции.

## Что уже готово

- Полная реализация урока 3.3 и её регрессионные проверки.
- Ключ с fingerprint вопроса, режима и полных пар истории; отдельные namespace для promptVersion/systemPromptHash/modelDigest.
- REST API `POST /api/queries/cached/plan` и проверка актуального model digest.
- DTO `Reply(hit, plan, incrementalPreparationCalls)`.
- Настройки cache: 128 записей, TTL 5 минут, Clock.systemUTC.
- Девять acceptance-тестов без настоящих моделей и отдельный бланк сдачи.

Реализуйте только метод:

```java
public synchronized Lookup getOrCompute(Key key, Supplier<QueryPlanner.Plan> supplier)
```

в [QueryPlanCache.java](src/main/java/dev/myfrei/repopilot/cache/QueryPlanCache.java). В проекте один новый TODO. Внутренние Entry, поля storage/clock/capacity/ttl, фабрика ключа и предикат cacheable предоставлены. Не переписывайте QueryPlanner, RRF, RAG или тесты, чтобы получить зелёный результат.

## Контракт TTL/LRU

1. Ключ и supplier обязательны. Успешный supplier возвращает ненулевой immutable `QueryPlanner.Plan`.
2. Запись пригодна только пока `now < expiresAt`. В момент `now == expiresAt` она уже истекла.
3. TTL отсчитывается от завершения вычисления, а не от его начала. Cache hit не продлевает TTL.
4. Перед обработкой обращения убирайте истёкшие записи. На hit supplier не вызывается, возвращается тот же Plan и `hit=true`.
5. Hit делает запись последней использованной. Успешное новое сохранение тоже считается использованием.
6. После вставки число записей не превышает capacity. При переполнении удаляется запись, использованная раньше остальных: LRU.
7. Ошибка supplier передаётся вызывающему коду. Она не сохраняется как результат; следующий вызов снова вычисляет план.
8. Некэшируемый план возвращается с `hit=false`, но не добавляется в storage.
9. Параллельные обращения к одному ключу не должны вычислять один и тот же пригодный план несколько раз. Для учебной реализации допустима синхронизация всего метода, включая supplier. Это простое ограничение пропускной способности: разные ключи тоже будут ждать.

Уже предоставленный предикат `cacheable` принимает только планы с положительным числом preparation calls, запрошенным и эффективным режимом ключа и совпадающими modelDigest/promptVersion/systemPromptHash. Он отклоняет `DIRECT`, `QUERY_PREPARATION_FALLBACK`, отсутствие дополнительных вариантов с effectiveMode=DIRECT и несовпадающую идентичность.

Не превращайте fallback в исключение ради cache. Базовый planner по-прежнему может вернуть исходный запрос с предупреждением. Такой результат просто вычисляется заново при следующем обращении.

## Из чего состоит ключ

`QueryPlanCache.keyOf` сериализует **mode + точный исходный question + упорядоченные полные history turns**, затем вычисляет SHA-256. Key хранит fingerprint и отдельно mode, promptVersion, systemPromptHash и проверенный chat model digest. В key нет plaintext вопроса или истории.

Изменение предыдущего assistant answer тоже меняет ключ: contextual rewrite зависит от полной пары истории. Отличие вопроса, режима, prompt version, system hash или digest не должно попадать в ту же запись. Никакой нормализации пробелов или удаления history при построении ключа добавлять не надо.

`topK` и `threshold` не входят в ключ, потому что это параметры последующего retrieval. Они не используются QueryPlanner для подготовки строк. Запрос с другим topK вправе переиспользовать подготовку, но должен заново искать документы с новыми параметрами. Новый API возвращает только query plan и вообще не выполняет поиск.

Версия prompt — часть протокола разработки. Если изменили шаблон rewrite/expansion, обновите promptVersion или очистите cache перезапуском приложения. Конфигурация model/context/options фиксирована для экземпляра приложения; это не распределённый cache со сменой конфигурации на лету.

SHA-256 ключа не обезличивает содержимое значения: Plan хранит исходный вопрос и подготовленные варианты. Cache только в памяти текущего процесса, без диска и логирования payload. Не добавляйте дамп storage в журнал.

## Runtime guard и счётчики

Готовый CachedQueryPlanController читает digest до обращения к cache и проверяет его после вычисления/получения. При изменении возвращается `QUERY_MODEL_CHANGED`. Если модель поменялась во время supplier, guard выбрасывает ошибку **до сохранения**. Следующий стабильный запрос должен вычислиться заново.

Режим DIRECT вообще не использует cache и не требует чтения model digest. Он возвращает обычный прямой план. Для остальных режимов cache hit по-прежнему выполняет проверки digest через `/api/tags`; отсутствие новых preparation calls не означает отсутствие любой работы или нулевую задержку.

У Plan поля `preparationCalls` и `preparationMs` описывают **историческое создание этого значения**. На hit Plan не переписывается. Поэтому API содержит отдельное поле `incrementalPreparationCalls`:

| Обращение | hit | plan.preparationCalls | incrementalPreparationCalls |
| --- | --- | --- | --- |
| Первый EXPANDED без истории | false | Обычно 1 | 1 |
| Повтор того же пригодного плана | true | То же историческое 1 | 0 |
| Новый режим/история/digest | false | Фактическая стоимость нового плана | Та же стоимость |
| DIRECT | false | 0 | 0 |
| Fallback | false | Число предпринятых вызовов | То же число |

Не суммируйте plan.preparationCalls по cache hits как новые вызовы модели. Не записывайте в Plan нули на hit: тогда потеряется происхождение значения.

## Демонстрация API

Запустите Ollama с chat-моделью из lecture и приложение. Query configuration включает также RAG beans; для обычного полного запуска поднимите Compose и опубликуйте индекс по инструкции лекции. Сам `/cached/plan` вызывает только подготовку, без SQL retrieval и без генерации ответа.

```bash
docker compose up -d
./mvnw spring-boot:run
```

Дважды выполните один и тот же запрос:

```bash
curl -sS -X POST http://127.0.0.1:8080/api/queries/cached/plan \
  -H 'Content-Type: application/json' \
  -d '{"question":"Какой профиль и порт нужны для запуска TaskShelf?","mode":"EXPANDED","history":[],"topK":4,"threshold":0.35}'
```

Первое обращение будет miss. Второе станет hit, **если** модель вернула валидные дополнительные варианты и итоговый Plan принят предикатом cacheable. Если модель вернула только исходную строку или fallback, повтор остаётся miss — это ожидаемый контракт.

Поменяйте `topK`: подготовка может остаться hit. Поменяйте question или history: должен быть miss. Для наглядного CONTEXTUAL запроса:

```json
{
  "question":"А какой у него порт?",
  "mode":"CONTEXTUAL",
  "history":[{"question":"О каком проекте речь?","answer":"О TaskShelf."}],
  "topK":4,
  "threshold":0.35
}
```

Не утверждайте, что hit улучшил качество ответа: этот API ответа не генерирует. Для измерения задержки и стоимости подготовки фиксируйте одинаковые условия и не подменяйте реальные измерения fixtures.

## Проверки

Выполняйте из homework:

```bash
./mvnw -DskipTests package
./mvnw -Dtest='*Test,!QueryPlanCacheAcceptanceTest' test
npm ci
npm test
./mvnw -Dtest=QueryPlanCacheAcceptanceTest test
```

Изначально package собирается, базовые Java/JS проверки зелёные. Новые acceptance ожидаемо красные из-за одного TODO. После реализации запустите полный набор, включая реальную БД в Docker:

```bash
./mvnw verify -Ppgvector-it
npm test
```

Новый [QueryPlanCacheAcceptanceTest](src/test/java/dev/myfrei/repopilot/cache/QueryPlanCacheAcceptanceTest.java) проверяет девять сценариев: hit и текущую стоимость; inclusive TTL после computation; LRU promotion и capacity; историю/режим/вопрос; namespace prompt/system/digest; некэшируемые планы; ошибку supplier; изменение runtime identity и обход DIRECT; конкурентные обращения. Временные проверки используют управляемый Clock, а не ожидание пяти минут.

## Критерии приёмки

- Реализован один новый cache policy, базовые методы lecture не вырезаны и не переписаны.
- Истечение TTL и LRU — разные правила; hit продвигает LRU, но не обновляет expiresAt.
- Ошибки, fallback, DIRECT и несовместимые планы не сохраняются.
- Результаты retrieval/ответы не кэшируются, history и digest изолированы.
- Новые acceptance и старые regression/IT проходят без изменения тестов.
- В [SUBMISSION.md](SUBMISSION.md) разделены измеренные preparation calls и исторические поля Plan, записаны ограничения.

Необязательное исследование: измерить долю полезных cache hits для повторных вопросов на собственном DEV-наборе. Запишите отдельно cache hit rate, новые preparation calls и end-to-end время. Не выбирайте TTL по HOLDOUT и не объявляйте LRU/TTL механизмом повышения качества RAG.
