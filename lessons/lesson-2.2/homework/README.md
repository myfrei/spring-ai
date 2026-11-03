# ДЗ 2.2. Инкрементальные embeddings и удаление stale chunks

[Материалы урока](../README.md) · [Лекция](../lecture/README.md) · [Приёмочные тесты](src/test/java/dev/myfrei/repopilot/homework/IncrementalReuseHomeworkAcceptanceTest.java) · [Отчёт по ДЗ](SUBMISSION.md)

## Чем задача отличается от лекции

Лекция повторно использует только целый неизменённый снимок. ДЗ переиспользует отдельные векторы неизменённых encoder inputs, когда корпус или splitter изменились, и пересчитывает только отсутствующие входы.

В homework **уже включено готовое базовое приложение lecture**. Реализовывать его заново или переносить ответы из лекции не требуется. Новый учебный код находится в `dev.myfrei.repopilot.homework`; незаполнен только метод нового класса `IncrementalEmbeddingPlanner`. REST-включение и остальная инфраструктура предоставлены.

| Что дано | Что нужно добавить |
| --- | --- |
| Весь работающий срез лекции и его регрессионные тесты | Новую policy, которой в лекции нет |
| Новый API, DTO и вызов policy в продукте | Самостоятельный алгоритм по контракту ниже |
| 6 отдельных acceptance-проверок | Собственное объяснение выбранного поведения и его ограничений |

## Запуск проверок

Из этой папки, с JDK 25:

```bash
./mvnw -DskipTests package
./mvnw -Dtest='*Test,!*HomeworkAcceptanceTest' test
npm ci
npm test
./mvnw -Dtest=IncrementalReuseHomeworkAcceptanceTest test
```

Каркас собирается. Базовые Java/JS-проверки должны быть зелёными; последние 6 acceptance сначала красные из-за явного TODO. После реализации запустите полный `./mvnw test` и `npm test`.

Не подменяйте реализацию константами и не отключайте тесты. После выполнения задания должны пройти базовые и новые приёмочные проверки.

## Контракт инкрементального плана

Реализуйте `IncrementalEmbeddingPlanner.plan(current, identity, previous)`. Верните две immutable коллекции: reusable (EmbeddedChunk с **текущими** metadata) и missing (Chunk, для которого нужен inference).

1. Текущие chunk ID должны быть уникальны.
2. Старые vectors совместимы только при полном равенстве EmbeddingIdentity: model, digest, dimensions, inputFormat. Совпадения длины вектора недостаточно.
3. Ключ переиспользования зависит от точного encoder input: title, section и text. Используйте length-prefixed Hashes.identity. Путь/ordinal/новый chunkId/corpusVersion сами по себе не меняют embedding, если фактический encoder input тот же.
4. Title или section изменились — prefix encoder изменился, поэтому это missing, даже если raw text прежний.
5. Кешированный vector допускается только нужной длины, конечный и ненулевой. Не обрезайте и не дополняйте его.
6. Для reuse создайте EmbeddedChunk с новым current Chunk, а не возвращайте старые metadata.
7. Сохраняйте порядок current отдельно в reusable/missing. Отсутствующие в current старые чанки не попадают ни в одну коллекцию.

В этой версии gateway input имеет формат `title: <title> / <section> | text: <text>`. При изменении этого формата нужно изменить inputFormat identity. Нельзя переносить reuse policy на другой encoder без проверки его контракта.

## Предоставленная оркестрация

IngestionService уже умеет `incrementalIndex(options, planner)`: общий busy guard обычного и нового endpoint, batch только для missing, проверка vectors, повторная проверка identity и атомарная публикация **целого** нового artifact. Ваш TODO отвечает за план reuse; повторять ETL лекции не нужно. В результате API есть reusedVectors и embeddedVectors.

```bash
curl -sS http://127.0.0.1:8080/api/ingestion/incremental/index \
  -H 'Content-Type: application/json' \
  -d '{"strategy":"MARKDOWN_SECTIONS","maxCodePoints":600,"overlapCodePoints":80}'
```

Для настоящей индексации нужны Ollama и embeddinggemma:300m. Unit/контрактным тестам веса не нужны. Учебный эксперимент проводите на отдельном corpus/индексе: сначала исходный снимок, затем изменённый документ, затем удалённый документ. Повтор неизменённого целого снимка по-прежнему может завершиться раньше planner без inference.

## Приёмка нового поведения

- Изменён один chunk input — остальные совместимые векторы не пересчитываются.
- Новый chunkId с тем же input получает старый vector и свежие metadata.
- Смена digest/input format исключает все старые vectors.
- Новый снимок не содержит удалённых chunks и имеет текущий fingerprint.
- Ошибка нового embedding не публикует частичный reuse.

Сдайте policy и [SUBMISSION.md](SUBMISSION.md), укажите реальные числа reused/embedded и способ проверки количества вызовов encoder. Не называйте экономию вызовов улучшением семантического качества.

Усложнение: оценить стоимость hashing/cache lookup и memory overhead на более крупном корпусе; не смешивать результаты разных encoder profiles.
