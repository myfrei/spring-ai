# ДЗ 2.3. Разнообразие источников перед сборкой RAG

## Чем задача отличается от лекции

Лекция передаёт ранжированный префикс retrieval. ДЗ удаляет точные и перекрывающиеся повторы, ограничивает монополию одного документа и перенумеровывает фактически выбранные sources до применения бюджета.

В homework **уже включено готовое базовое приложение lecture**. Реализовывать его заново или переносить ответы из лекции не требуется. Новый учебный код находится в `dev.myfrei.repopilot.homework`; незаполнен только метод нового класса `EvidenceSelector`. REST-включение и остальная инфраструктура предоставлены.

| Что дано | Что нужно добавить |
| --- | --- |
| Весь работающий срез лекции и его регрессионные тесты | Новую policy, которой в лекции нет |
| Новый API, DTO и вызов policy в продукте | Самостоятельный алгоритм по контракту ниже |
| 5 отдельных acceptance-проверок | Собственное объяснение выбранного поведения и его ограничений |

## Запуск проверок

Из этой папки, с JDK 25:

```bash
./mvnw -DskipTests package
./mvnw -Dtest='*Test,!*HomeworkAcceptanceTest' test
npm ci
npm test
./mvnw -Dtest=EvidenceSelectionHomeworkAcceptanceTest test
```

Каркас собирается. Базовые Java/JS-проверки должны быть зелёными; последние 5 acceptance сначала красные из-за явного TODO. После реализации запустите полный `./mvnw test` и `npm test`.

Не заменяйте тесты константами и не выключайте их. Проверено, что во временной копии с заменой **только одного нового policy-файла** неизменённые тесты проходят. Эталонного решения в homework нет.

## Контракт отбора evidence

Реализуйте `EvidenceSelector.select(ranked, maxPerDocument, maxTotal, overlapThreshold)`. Это отдельный шаг после настоящего retrieval и перед RagPlanner. Он не является reranker: score не пересчитывается.

1. Разрешённые limits 1..8; overlapThreshold конечный в 0..1. Все ranges включительны, startLine>=1, endLine>=startLine. Невалидные данные отклоняются, даже если чанк затем мог быть отброшен.
2. Обходите вход в исходном ранжированном порядке. Точный повтор chunkId отбрасывается, остаётся первое появление.
3. Ограничьте число принятых чанков одного documentId. Считаются только принятые chunks.
4. Для двух chunks одного документа overlap = число общих строк / длина более короткого диапазона. Отбрасывайте новый чанк при положительном пересечении и overlap>=threshold. Разные документы по номерам строк не сравниваются. При threshold=0 непересекающиеся ranges всё равно сохраняются.
5. Остановитесь после maxTotal принятых chunks.
6. Выжившие sources перенумеруйте S1..Sn без пропусков. Сохраните chunkId, documentId, path/title/section, диапазон, textHash, score и raw text. Входные объекты не изменяются.

Готовый DiverseRagService делает один search с topK=8, вызывает вашу policy, затем штатный budget planner и QuestionAnswerAdvisor с фиксированным набором. Повторного embedding или SQL внутри advisor нет.

## Новые маршруты ДЗ

```bash
curl -sS http://127.0.0.1:8080/api/rag/diverse/search \
  -H 'Content-Type: application/json' \
  -d '{"message":"Что проверить при SQLITE_BUSY?","maxPerDocument":1,"maxTotal":4,"overlapThreshold":0.5,"threshold":0.35}'
curl -N http://127.0.0.1:8080/api/conversations/CONVERSATION_UUID/rag/diverse/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"Что проверить при SQLITE_BUSY?","maxPerDocument":1,"maxTotal":4,"overlapThreshold":0.5,"threshold":0.35}'
```

Подготовьте настоящий индекс по лекции; UUID создайте штатным API. Preview возвращает retrievedCount, removedChunkIds и выбранный RagSearchResult. RagReport потокового ответа относится к последующему budget-отбору, поэтому число removed по diversity смотрите в отдельном preview. Обычный RAG лекции не меняется.

## Проверки и эксперимент

После зелёных быстрых тестов выполните:

```bash
./mvnw verify -Ppgvector-it
```

Профиль требует Docker и проверяет регрессии настоящего pgvector. Новые acceptance policy работают без Docker на контролируемых hit lists. Отдельно сравните baseline и diverse pipeline на одинаковых вопросах/данных и новых диалогах. Докажите конкретный пример: отбор уменьшил повторение, но сохранил либо потерял нужное свидетельство. Не называйте diversity универсальным улучшением — лимит на документ может убрать вторую необходимую часть ответа.

Сдайте policy и [SUBMISSION.md](SUBMISSION.md). Укажите maxPerDocument/maxTotal/overlapThreshold, исходные и выбранные IDs, причины потерь и проверку меток.

Усложнение: заменить line-overlap proxy пересечением символов в исходном документе и показать случай, где номера строк слишком грубы.
