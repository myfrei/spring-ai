# Worksheet: DIRECT / CONTEXTUAL / EXPANDED

Бланк студента; результаты не заполнены. Проверка готового lecture: **223 быстрых Java + 24 pgvector IT + 104 JS зелёные**, с HTTP-fixtures без настоящих весов. Эти числа не являются оценкой качества модели. [Лекция](../README.md) · [Условия ДЗ](../../homework/README.md) · [Проверка материалов](../../VERIFICATION.md).

## 1. Зафиксированные условия

| Поле | Значение |
| --- | --- |
| Дата, ОС, CPU/GPU/RAM | |
| Git revision проекта | |
| Java / Spring AI / Ollama | |
| Chat model ID и digest | |
| Embedding model / digest / dimensions / inputFormat | |
| corpusVersion / indexFingerprint | |
| datasetVersion / split | |
| promptVersion / systemPromptHash | |
| Изменения QUERY_CONTEXTUALIZE / QUERY_EXPAND / финальной инструкции | |
| window / output reserve / safety margin | |
| preparation: temperature / numPredict / inputBudget (window−128−256) | |
| generation: temperature / output limit | |
| topK на запрос / threshold / fusion limit / RRF constant | |
| Холодный/тёплый runtime; порядок режимов; число повторов | |

Не меняйте корпус, encoder и chat digest одновременно с исследуемой policy. Выберите DEV-вопросы и критерии до запуска. HOLDOUT используется после фиксации решения.

## 2. Гипотеза и критерий решения

- Какую проблему baseline исправляем: ____________________
- Какие DEV case IDs выбраны и почему: ____________________
- Как проверяем answer/citation correctness по источникам: ____________________
- Допустимый дополнительный totalMs/model calls: ____________________
- Что будет считаться регрессией: ____________________

## 3. Проверка плана с явной историей

/api/queries/plan не сохраняет plan для следующего endpoint. Каждый запрос запускает собственную подготовку. /compare использует пустую историю; CONTEXTUAL с историей исследуйте через /plan, /prepare и /call с явным JSON history.

| Case / исходный вопрос | History snapshot | Requested/effective mode | Queries в точном порядке | Preparation calls | Notes / fallback |
| --- | --- | --- | --- | --- | --- |
| | | | | | |
| | | | | | |
| | | | | | |

При QUERY_CONTEXT_TOO_LARGE проверьте, что отклонённый до inference этап не увеличил preparationCalls и что fallback не отменил финальный бюджет. Проверьте original first, не более трёх уникальных точных строк и отсутствие выдуманных identifiers. Сохраните JSON-входы/ответы рядом с worksheet либо укажите путь к ним.

## 4. Retrieval до и после бюджета

| Case / mode | Searches | Ranked chunk IDs | Fusion score / support / bestCosine | IDs в rag.sources | Потерянное/найденное свидетельство |
| --- | --- | --- | --- | --- | --- |
| | | | | | |
| | | | | | |

RRF score не равен cosine или вероятности качества. Ranks относится к fusion до budget selection; rag.sources — к реально подготовленному prompt. Проверьте raw текст по fingerprint/chunkId, а не только заголовки.

## 5. Полная стоимость

| Case / repetition / mode | Preparation calls / ms | Searches / retrievalMs | Final chat вызван? | TotalMs | Final provider tokens | Ошибка / NO_EVIDENCE / COMPLETED |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |
| | | | | | | |

Отдельно перечислите query embedding calls, tags requests и предварительные ingestion costs. Provider token counters ответа /call относятся к финальной генерации, не ко всей подготовке. TotalMs — ожидание полного JSON, не TTFT. Ошибки и NO_EVIDENCE не получают «победу по скорости» за отсутствие ответа.

## 6. Парное сравнение

Для /api/queries/compare указывайте явные split/caseId. Сохраните исходный JSON и проверьте chatModelDigest, datasetVersion, оба indexFingerprint и order. Встроенный порядок DIRECT→EXPANDED фиксирован; отметьте возможный прогрев. Одной пары недостаточно для статистического вывода.

| Case / repetition | DIRECT: факты/цитаты | EXPANDED: факты/цитаты | Что изменилось в evidence | Delta полной задержки / вызовов | Вывод по case |
| --- | --- | --- | --- | --- | --- |
| | | | | | |
| | | | | | |
| | | | | | |

Покажите улучшения, регрессии и отсутствие изменения. Не прячьте отдельную потерянную цитату за ростом средней оценки. Автоматические fixtures подтверждают контракт; строки о качестве заполняются только после настоящей генерации и проверки источника.

## 7. Решение на DEV и проверка HOLDOUT

- Зафиксированное решение и его цена: ____________________
- Какие альтернативы отвергнуты, какие данные это подтверждают: ____________________
- Version/revision замороженного pipeline: ____________________
- HOLDOUT case IDs, исходные JSON и результат: ____________________
- Что не проверено и где вывод ограничен: ____________________

Не меняйте threshold/prompt после просмотра HOLDOUT, продолжая называть его независимой проверкой. Новый цикл требует нового дизайна оценки.

## 8. Отдельная проверка кеша в ДЗ

- Ключ и identity: ____________________
- Первый miss / повторный hit, реальные preparation calls: ____________________
- TTL expiry с контролируемыми часами: ____________________
- LRU eviction и размер: ____________________
- Смена history/mode/digest/promptVersion: ____________________
- Что по-прежнему стоит retrieval/generation после cache hit: ____________________

Кеш query plan не является кешем финального ответа или KV-cache Ollama. Приёмочный алгоритм и требования к fallback — в актуальном README ДЗ.
