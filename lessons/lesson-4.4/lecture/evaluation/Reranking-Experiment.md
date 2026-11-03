# Бланк эксперимента: NONE / LEXICAL / LLM

Результаты не заполнены. [Лекция](../README.md) · [ДЗ](../../homework/README.md) · [Проверка контрактов](../../VERIFICATION.md).

## 1. Условия

| Поле | Фактическое значение |
| --- | --- |
| Дата / ОС / CPU / GPU / RAM | |
| Git revision / Java / Spring AI / Ollama | |
| Chat model ID / digest | |
| Embedding ID / digest / dimensions / inputFormat | |
| corpusVersion / indexFingerprint | |
| datasetVersion / split / case IDs | |
| System prompt hash / query prompt version | |
| Rerank algorithmVersion / изменения инструкции | |
| Query mode / history / topK / threshold | |
| Candidate count / keep | |
| Reranker window / output reserve / safety margin / inputBudget | |
| Reranker temperature / output limit | |
| Final window / output reserve / temperature | |
| Число повторов / порядок / холодный или тёплый запуск | |

Параметры выбираются на DEV до итогового HOLDOUT. Не меняйте корпус, encoder и chat weights внутри одной сравниваемой пары.

## 2. Гипотеза

- Где baseline теряет нужное свидетельство: ____________________
- Есть ли оно уже среди candidates: ____________________
- Что именно ожидается от переупорядочивания: ____________________
- Как проверяем факты/цитаты в ответе: ____________________
- Допустимая цена дополнительного inference/total latency: ____________________
- Заранее заданное условие отказа от изменения: ____________________

Отдельно отметьте неоднозначность follow-up: scoring prompt не содержит history, хотя contextual retrieval и финальный ответ могут её использовать.

Если текста нет среди candidates, это проблема полноты retrieval; reranker его не восстановит. Не называйте рост score улучшением качества без проверки свидетельства.

## 3. Кандидаты и порядок

| Case / repeat / mode | Исходные chunk IDs | Новый порядок IDs | Original rank / rerank rank | Cosine отдельно от rerank score | Kept до бюджета | IDs финального rag.sources |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |
| | | | | | | |
| | | | | | | |

Сохраните исходные JSON и snapshot identity. По fingerprint/chunkId прочитайте raw text и проверьте, что source text/hash не изменился. Кандидаты, kept и реально включённые sources — разные этапы.

## 4. Надёжность LLM

| Проверка | Вход/наблюдение | Requested/effective strategy | Inference attempts | Notes | Итог |
| --- | --- | --- | --- | --- | --- |
| Успешный строгий JSON со всеми IDs | | | | | |
| Неверный/повторный/пропущенный ID | | | | | |
| Score строкой, null, вне диапазона или nonfinite | | | | | |
| Повреждённый JSON / extra text / unfinished | | | | | |
| Prompt больше inputBudget | | | | | |
| Model digest изменился после ошибки | | | | | |

Часть таких условий воспроизводится fixtures, а не ручным вмешательством в реальные веса. Отмечайте способ проверки. Oversize до inference не должен скрыто запускать reranker; fallback обязан сохранить baseline без repair/retry. Смена digest не является обычным успешным fallback.

## 5. Стоимость этапов

| Case / mode | Query preparation calls/ms | Searches / retrievalMs | Shared retrieval? | Rerank attempts/ms | Rerank provider tokens | Final provider tokens | TotalMs | Outcome |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| | | | | | | | | |
| | | | | | | | | |

Usage reranker и финального ответа нельзя смешивать. Token usage query preparation может не возвращаться этим API. Metadata/tags HTTP тоже расходуют время, хотя не являются inference. Ошибки и NO_EVIDENCE не получают преимущество за отсутствие финального ответа.

В /compare retrieval выполняется один раз для общей пары. Branch report с searches=0/retrievalMs=0/sharedRetrieval=true означает уже выполненную общую работу, а не бесплатный поиск. Для оценки отдельного запроса учитывайте shared cost один раз плюс работу нужной ветки. Время всего compare включает обе ветки и не равно latency одного /call.

## 6. Сравнение на общем snapshot

Для /api/reranking/compare укажите явные split/caseId, strategy и keep. Штатный compare использует DIRECT-поиск общего snapshot, затем NONE и выбранную стратегию в фиксированном порядке. Отметьте bias прогрева; эта пара не изолирует все runtime-факторы.

| Case / repeat | NONE: факты/цитаты | Candidate: факты/цитаты | Потерянное/найденное свидетельство | Delta полной стоимости | Решение по case |
| --- | --- | --- | --- | --- | --- |
| | | | | | |
| | | | | | |
| | | | | | |

Укажите digest/index guards, реальные ошибки, fallback и отсутствие изменений. Один ответ и один запуск не доказывают устойчивого преимущества. Сохраняйте ухудшения отдельных cases, даже если средняя оценка выросла.

## 7. Offline-метрики ДЗ

Новый `/api/ranking/evaluate` работает без SQL/LLM и не оценивает текст ответа. Разметка relevanceByDocument относится к логическим document IDs. Каждая позиция rankedDocumentIds соответствует исходному ранжированному chunk slot, но содержит ID его документа: несколько chunks одного документа повторяют этот ID. Короткий logical document ID задания не следует путать с SHA-256 chunkId из runtime; при переносе реального поиска явно укажите mapping.

| Набор / стратегия | K | Ranked slots | Positive gold IDs / grades | nDCG@K | RR@K | Recall@K | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| | | | | | | | |
| | | | | | | | |

Повтор документа занимает исходный slot, gain получает только первое вхождение; нельзя убрать дубликаты до cutoff и заполнить место следующим документом. Unjudged имеет gain0 по учебному контракту, что не доказывает его нерелевантность. NO_GOLD означает отсутствие positive annotations, а не доказанную неответимость; undefined metrics не включаются как нули в среднее.

Для серии SCORED cases отдельно вычислите MRR как mean(rr) на одинаковом наборе SCORED cases сравниваемых стратегий; API одного observation этого среднего не возвращает. NO_GOLD исключается из этого среднего и показывается отдельным счётчиком.

Сопоставьте offline-рейтинг с ручной проверкой ответа. Высокий nDCG или recall логических документов не доказывает наличие конкретной цитаты и faithfulness генерации.

## 8. Решение и HOLDOUT

- Зафиксированная стратегия/keep/параметры на DEV: ____________________
- Доказанные преимущества, регрессии и дополнительные расходы: ____________________
- Замороженные revision/model/index/algorithmVersion: ____________________
- HOLDOUT inputs и результаты: ____________________
- Что осталось непроверенным: ____________________

После просмотра HOLDOUT не подбирайте по нему threshold/prompt, продолжая считать его независимой проверкой. Разделяйте результаты fixtures и реальную генерацию; не заполняйте таблицы предполагаемыми ответами модели.
