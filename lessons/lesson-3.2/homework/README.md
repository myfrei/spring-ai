# ДЗ 3.2. Тематический профиль источников

[Материалы урока](../README.md) · [Лекция](../lecture/README.md) · [Приёмочные тесты](src/test/java/dev/myfrei/repopilot/advisor/DocumentScopeAcceptanceTest.java) · [Отчёт по ДЗ](SUBMISSION.md)

В [лекции](../lecture/README.md) вы построили собственные advisors и подключили их к call/stream. Здесь **вся реализация лекции уже готова**, включая diagnostics из 3.1. Повторно писать SnapshotRagAdvisor, ServiceRagAdvisor или RAG-поиск не требуется.

Новая задача продукта: пользователь выбирает тему ответа. Для диагностики он получает сведения из runbook, для архитектуры — из overview/runtime. Даже высоко оценённый SQL-поиском чанк другой темы не должен попасть в prompt и RagReport. Эта функция отсутствует в lecture.

## 1. Что уже работает

- Обычный UI, история, streaming, CHAT/FULL_CONTEXT/RAG, benchmark и диагностика.
- Snapshot advisor для готового плана и service advisor для нового call.
- POST `/api/advisors/call`: поведение лекции без тематического ограничения.
- POST `/api/advisors/scoped/call`: готовый controller с новым полем profile, подключённый к новому policy hook.
- `RagPreparationService`: search → selection → plan; policy вызывается до расчёта бюджета.
- Семь дополнительных приёмочных тестов. Они проверяют новое поведение, не требуют копировать методы из lecture.

Единственный новый TODO находится в [DocumentScope.java](src/main/java/dev/myfrei/repopilot/advisor/DocumentScope.java). Реализуйте этот метод вместо заглушки UnsupportedOperationException. Он вызывается только новым scoped endpoint, поэтому исходная функция урока работает до решения ДЗ.

## 2. Запуск и исходное состояние тестов

Java 25, Docker только для pgvector-профиля; Node 24 для унаследованного UI. Команды из homework:

```bash
./mvnw -DskipTests package
./mvnw '-Dtest=*Test,!DocumentScopeAcceptanceTest' test
npm ci
npm test
./mvnw -Dtest=DocumentScopeAcceptanceTest test
```

Первый шаг должен собрать приложение, а регрессионные тесты готовой базы — пройти. Последняя команда запускает семь новых приёмочных проверок: до реализации DocumentScope они ожидаемо не проходят. Реализуйте метод и повторите проверку, сохранив тесты без изменений.

После реализации:

```bash
./mvnw test
./mvnw verify -Ppgvector-it
npm test
```

Реальный запуск и подготовка индекса описаны в [лекции](../lecture/README.md#2-запуск-готового-проекта). Запустите Docker/Ollama и опубликуйте corpus в pgvector через прежний UI. Новому API нужны те же index/model profile, что и обычному RAG.

## 3. Контракт новой policy

Метод `DocumentScope.select(Profile profile, RagSearchResult found)` возвращает **новый** RagSearchResult.

| Профиль | Разрешённые documentId |
| --- | --- |
| TROUBLESHOOTING | runbook |
| ARCHITECTURE | overview, runtime |

Правила:

1. Сервер определяет разрешённые documentId. Клиент выбирает только enum-профиль, не произвольное SQL-выражение или список документов.
2. Фильтруйте по documentId, не по path, title, тексту или наличию слова «архитектура».
3. Сохраняйте исходный rank. Пересортировка по ID или score — изменение контракта.
4. Перенумеруйте оставшиеся метки подряд S1…Sn. Если исходный S2 остался первым, он должен стать S1.
5. Сохраните неизменными manifest, chunkId, documentId, path, title, section, startLine, endLine, textHash, score и текст каждого оставшегося hit.
6. Не изменяйте входной RagSearchResult. Список результата должен быть неизменяемым, как в существующем record.
7. При null profile выбросьте IllegalArgumentException.
8. Если подходящих candidates нет, верните пустой hits. Не включайте другой профиль и не возвращайте исходный список.
9. Не вызывайте LLM, embedding или searcher из DocumentScope. Один search в service path уже выполнен. Повторный поиск изменил бы стоимость и семантику эксперимента.

Контекст собирается только после policy. Если первый, запрещённый профилем чанк огромный, он не должен исчерпать бюджет разрешённого чанка. Один из тестов именно поэтому содержит слишком большой чужой фрагмент.

Существующий ServiceRagAdvisor преобразует пустой разрешённый набор в NO_EVIDENCE до вызова chat-модели. Controller возвращает HTTP 409; это штатный отказ по контракту нового API, а не ошибка модели.

## 4. Подключение уже подготовлено

```text
ScopedAdvisorController
  → AdvisorCallService.call(request, selection)
  → InputContractAdvisor (-100)
  → ServiceRagAdvisor (0)
  → RagPreparationService
      → RagSearcher.search
      → DocumentScope.select       ← ваша новая функция
      → RagPlanner.plan
  → SnapshotRagAdvisor.apply
  → ChatModel.call
  → after: report metadata
```

Изменять базовые advisors, RagPrompt, budget и SQL workspace filters для выполнения задания не требуется. Код решения должен оставаться в новой policy; дополнительную декомпозицию внутри пакета можно выбрать самостоятельно.

Пример запроса после решения:

```bash
curl -sS http://127.0.0.1:8080/api/advisors/scoped/call \
  -H 'Content-Type: application/json' \
  -d '{"message":"Какой порт использует TaskShelf?","profile":"TROUBLESHOOTING","topK":8,"threshold":0.2}'
```

В answer модель может ответить по runbook. В `rag.sources` допустим только documentId=runbook. Повторите вопрос с ARCHITECTURE: runbook не должен появиться в prompt/report; возможны NO_EVIDENCE или честный ответ о недостатке данных. Пустой результат нельзя превращать в ответ из запрещённого профилем документа.

## 5. Что проверяют семь тестов

Файл [DocumentScopeAcceptanceTest.java](src/test/java/dev/myfrei/repopilot/advisor/DocumentScopeAcceptanceTest.java):

1. TROUBLESHOOTING оставляет runbook, сохраняет manifest/data и исходный список.
2. ARCHITECTURE исключает runbook и перенумеровывает оставшийся overview в S1.
3. Пустой разрешённый набор не даёт fallback и не вызывает модель.
4. Настоящий ChatClient + RecordingModel получает только разрешённые данные; report содержит те же источники, retrieval выполняется один раз.
5. runtime/overview сохраняют исходный rank и все поля.
6. Policy работает до budget: запрещённый огромный первый чанк не мешает разрешённому.
7. null profile отклоняется; список результата неизменяемый.

RecordingModel возвращает заготовленный текст. Тесты доказывают, какие данные ушли в модель, и целостность отчёта. Они не доказывают, что реальные веса ответят правильно.

## 6. Эксперимент и ограничения

Заполните [Scope-Decision.md](evaluation/Scope-Decision.md), сравнив одинаковый вопрос в обычном call и двух профилях. Зафиксируйте источники, отсутствие/наличие ответа и ожидаемое объяснение.

Policy применяется **после SQL topK**. Если первые topK результатов относятся к другой теме, разрешённый набор может оказаться пустым, хотя нужный документ был бы на позиции topK+1. Увеличение topK меняет эксперимент; выполняйте его отдельно и записывайте условия.

Это тематическая policy формирования prompt, не механизм авторизации. Сервер уже прочитал все кандидаты поиска. Workspace/corpus/fingerprint/model-digest filters остаются обязательными в SQL. Если нужен контроль доступа к данным, его проектируют до чтения, а не называют эту policy защитой данных.

## 7. Сдача

Сдайте код новой policy, неизменённые проверочные тесты и заполненный [SUBMISSION.md](SUBMISSION.md). Приложите результаты package/test/pgvector-it/npm test и бланк эксперимента. Укажите, где использовали fixture, а где реальные веса. Отдельно объясните, почему выбранный профиль может законно отказать на вопрос, на который обычный RAG отвечает.
