# ДЗ 3.4. Offline evaluator качества ранжирования

В лекции построены режимы reranking NONE, LEXICAL и LLM. В этом задании они предоставлены **полностью готовыми**. Вы добавляете отдельный инструмент: детерминированно оцениваете сохранённый порядок document IDs по ручным graded judgments. Студент не пишет ещё один reranker и не повторяет код лекции.

Новый endpoint `POST /api/ranking/evaluate` уже подключён. Он принимает только ограниченные metadata, не обращается к модели, не запускает retrieval/SQL и не сохраняет вопросы, ответы или документы. Ваша задача — реализовать один pure policy метод [RankingMetrics.evaluate](src/main/java/dev/myfrei/repopilot/rankingeval/RankingMetrics.java). API, DTO, валидация, error advice и восемь acceptance-тестов готовы.

## Что оцениваем

На входе один вопрос в форме metadata наблюдения: упорядоченные document IDs найденных **chunk slots**, relevance grade каждого размеченного документа и cutoff K. Сам вопрос и содержимое chunks не передаются.

```json
{
  "rankedDocumentIds": ["runbook", "runbook", "overview", "runtime"],
  "relevanceByDocument": {"runbook":3,"overview":2,"runtime":1},
  "k":3
}
```

Два первых chunks относятся к одному документу. Они занимают две реальные позиции выдачи. Для document-level оценки второй не приносит дополнительный gain; при этом следующий документ остаётся на третьей позиции, а не перемещается на вторую.

Оценка по documentId с однократным gain в исходных chunk slots — явная учебная конвенция. Это не готовый протокол MSMARCO или Cranfield; у nDCG встречаются разные gain/discount и правила дубликатов, а наш вариант полностью определён ниже.

Здесь измеряется качество ранжирования документов при ограниченном числе chunk slots. Это **не** проверка того, что выбранный chunk содержит конкретный факт, ответ LLM верен или цитата подтверждает утверждение. Для них остаются EvidenceCoverage, просмотр фактических sources и ручная оценка из 2.4/3.1.

## Готовый каркас

Новые файлы находятся в `src/main/java/dev/myfrei/repopilot/rankingeval`:

| Файл | Что предоставлено |
| --- | --- |
| RankingObservation | Immutable snapshot и ограничения metadata |
| RankingScore | Типизированный результат и статус SCORED/NO_GOLD |
| RankingMetrics | Единственный новый TODO — evaluate |
| RankingEvaluationController | Независимый HTTP API, строгие JSON types/root keys |
| RankingEvaluationConfiguration | Bean policy, без зависимостей от RAG/LLM |
| RankingEvaluationExceptionHandler | 400 с общим сообщением без отражения входного payload |

Весь исходный lecture pipeline и его тесты уже реализованы. Не вырезайте их и не меняйте проверки. Дополнительных библиотек не нужно: Java 25, Spring Boot 4.1.2 и Spring AI 2.0.2 остаются теми же.

## 1. Ограничения входа

`RankingObservation` содержит:

```java
List<String> rankedDocumentIds
Map<String, Integer> relevanceByDocument
int k
```

- Оба контейнера обязательны, размер каждого — от 0 до 100.
- K — целое от 1 до 20.
- ID соответствует `[a-z0-9-]{1,40}`. Paths, chunk content и произвольные строки не принимаются.
- Grade — целое 0, 1, 2 или 3; null запрещён.
- Повтор document ID в ranking разрешён: это несколько chunks одного документа.
- Map содержит одну оценку на document ID; её порядок не задаёт идеальную выдачу.
- Input копируется. Изменение внешнего List/Map после создания observation не меняет snapshot; evaluator не переставляет и не изменяет его элементы.

HTTP адаптер принимает строго три поля, проверяет raw типы до создания DTO. JSON `2.9`, `"2"` и null не преобразуются молча в integer grade; дробный/строковый K тоже отвергается. Не включайте глобальную Jackson coercion-настройку ради этого endpoint: предоставленная локальная проверка уже решает задачу и не меняет старые API.

## 2. Gold и неполная разметка

`gold = { documentId | relevanceByDocument[documentId] > 0 }`.

Уникальность gold задаёт Map. Grade 0 — документ с нулевым gain по данной разметке. Document ID, отсутствующий в Map, для расчёта также получает grade 0.

**Unjudged=0 — объявленное условие учебного расчёта, а не доказательство нерелевантности документа.** Для собственного исследования размечайте небольшой candidate pool полностью либо явно фиксируйте неполноту. Если полезный документ не размечен, evaluator способен занизить результат. Нельзя объяснять любую низкую метрику плохим reranker без проверки judgments.

Если нет положительных grades, верните `status=NO_GOLD` и **null для всех пяти** чисел: dcg, idcg, ndcg, rr, recall. Остальные счётчики всё равно содержат наблюдаемые значения. Пустая разметка может означать недостаток annotations; она сама не доказывает, что на вопрос невозможно ответить. Этот API не оценивает правильность отказа.

Если gold есть, но выдача пуста, это `SCORED`: DCG/nDCG/RR/recall равны 0, а IDCG положителен. Не смешивайте отсутствие релевантной выдачи с отсутствием базы для оценки.

## 3. Сначала cutoff по исходным slots

Берите первые `min(K, rankedDocumentIds.size())` элементов исходного списка. Нумерация rank начинается с 1.

Для повторного document ID внутри этого prefix gain равен нулю. Повтор сохраняет свою позицию. Не удаляйте duplicates до cutoff и не добавляйте документы с позиций K+1 и далее взамен повторов.

Пример: `[a,a,a,b]`, K=3 и единственный положительный gold `b`. Оцениваются только `[a,a,a]`; b не попал в cutoff. После `distinct().limit(K)` получились бы `[a,b]` и незаслуженный положительный результат. Такая реализация не принимается.

`uniqueRetrievedDocuments` — число разных IDs в исходном prefix, включая grade 0 и unjudged. `retrievedGoldDocuments` — число разных положительных gold IDs в том же prefix. Эти два счётчика отвечают на разные вопросы.

## 4. DCG и nDCG@K

Используем заранее объявленные gain и discount:

```text
gain(rel) = 2^rel - 1
weight(rank) = 1 / log2(rank + 1)
```

Для первого вхождения document ID в prefix вклад в DCG равен `gain(rel) * weight(rank)`. Для повторного — 0. Unjudged/grade0 также дают 0. Суммируйте вклады на исходных rank.

IDCG строится **от всей gold-разметки**, а не только от документов, которые уже удалось найти. Возьмите уникальные положительно оценённые документы, отсортируйте grades по убыванию и суммируйте первые K на идеальных ranks 1,2,… . Если gold меньше K, используйте все gold. Даже когда ranking пуст или короче K, знаменатель строится таким способом.

При gold>0:

```text
nDCG@K = DCG@K / IDCG@K
```

Итог находится в [0,1]; все числа конечны. Сравнение floating point в тестах допускает погрешность 1e-12. Не применяйте линейный gain `rel` вместо `2^rel-1` и не используйте натуральный логарифм без согласованного основания.

Для примера `[a,a,b,c]`, grades a=3,b=2,c=1, K=3:

```text
DCG = 7/1 + 0/log2(3) + 3/log2(4) = 8.5
IDCG = 7/1 + 3/log2(3) + 1/log2(4)
```

Снижение отражает занятый повтором slot и отсутствие c в prefix. Идеальный порядок не содержит повторов документов.

## 5. RR@K и Recall@K

RR использует бинарную релевантность: любой положительный grade считается relevant. Найдите первый rank положительного gold в исходном prefix:

```text
RR@K = 1 / firstRelevantRank
```

Если в prefix таких документов нет, верните 0. Более высокий grade не увеличивает RR; остальные релевантные документы тоже его не меняют. Одно наблюдение даёт reciprocal rank **RR@K**. Для серии вопросов **MRR@K = mean(rr)** по одинаковым SCORED-кейсам с теми же K/разметкой. NO_GOLD учитывается отдельным счётчиком и не превращается в ноль. Автоматическую агрегацию нескольких наблюдений этот API не выполняет.

```text
Recall@K = retrievedGoldDocuments / totalGoldDocuments
```

Для `[a,a,b,c]` из предыдущего раздела RR=1, recall=2/3. Для `[unjudged,zero,b,a]`, grades zero=0,b=3,a=1 и K=4 RR=1/3, recall=1. При K=2 обе метрики равны 0.

Recall и RR не различают grades 1,2,3; nDCG различает. Поэтому полный recall и RR=1 не гарантируют оптимальный graded order. Отдельные метрики нужны для разных наблюдений, а не для выбора самого красивого числа.

## 6. Результат

`RankingScore` содержит:

```text
status, k, evaluatedSlots, uniqueRetrievedDocuments,
retrievedGoldDocuments, totalGoldDocuments,
dcg, idcg, ndcg, rr, recall
```

Для NO_GOLD все пять Double nullable. Для SCORED все они заданы, IDCG>0, nDCG/RR/recall в [0,1]. `evaluatedSlots` равен размеру prefix до дедупликации.

Если сравниваете несколько режимов вручную, считайте среднее только по одинаковым SCORED-вопросам с одинаковыми K/разметкой. Рядом укажите число SCORED и число NO_GOLD. Не подставляйте нули вместо null и не скрывайте пропущенные кейсы. Для повторных запусков сохраните связь caseId/режим/порядок/версия разметки в отчёте вне API.

## 7. Демонстрация API

Запустите приложение обычным способом или без RAG, если работаете только с metadata:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--repopilot.rag.enabled=false
```

Evaluator не зависит от SQL, chat/embedding-моделей или наличия индекса. Он доступен независимо от query/rerank/evaluation feature flags. Приложение использует общую локальную HTTP-конфигурацию lecture; основной курс не требует внешнего аккаунта.

```bash
curl -sS -X POST http://127.0.0.1:8080/api/ranking/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"rankedDocumentIds":["runbook","runbook","overview","runtime"],"relevanceByDocument":{"runbook":3,"overview":2,"runtime":1},"k":3}'
```

До реализации TODO валидный запрос ожидаемо не вычисляет результат. После реализации сверьте формулу, счётчики slots/unique и recall=2/3. API не возвращает исходный ranked list или grades: ответ — только вычисленные числа и счётчики. Невалидный body даёт 400 `INVALID_RANKING_OBSERVATION` без отражения входных значений.

Для сравнения NONE/LEXICAL/LLM возьмите фактически выбранный упорядоченный candidate list одного DEV-вопроса из lecture, замените каждый chunk его documentId, не удаляя повторы, и оцените каждый порядок с одними grades и K. Не вызывайте reranker внутри evaluator и не запускайте повторный retrieval ради этой оценки.

Честно зафиксируйте происхождение ranking: ручной fixture, HTTP-fixture или результат настоящей модели. Фиксируйте исходный candidate pool, corpus/index, prompt/model digest и параметры. Изменение candidate pool, K или разметки меняет постановку сравнения; его нельзя спрятать под названием «новый reranker стал лучше».

## 8. Проверки и критерии приёмки

Из homework:

```bash
./mvnw -DskipTests package
./mvnw -Dtest='*Test,!RankingMetricsAcceptanceTest' test
npm ci
npm test
./mvnw -Dtest=RankingMetricsAcceptanceTest test
```

Каркас собирается. Старые Java/JS регрессии зелёные; восемь новых acceptance ожидаемо красные из-за единственного нового TODO. После реализации обязательно проверьте полный унаследованный pipeline, в том числе реальный PostgreSQL/pgvector в Docker:

```bash
./mvnw verify -Ppgvector-it
npm test
```

[RankingMetricsAcceptanceTest](src/test/java/dev/myfrei/repopilot/rankingeval/RankingMetricsAcceptanceTest.java) проверяет graded порядок, document duplicates, cutoff без refill, RR на исходных ranks, positive gold, пустую выдачу против NO_GOLD, immutable snapshot/границы и standalone HTTP без SQL/LLM. В HTTP-негативах есть дробные/строковые K/grades, null grade, лишние поля и malformed JSON; ответ не отражает входной private marker.

К сдаче нужны:

- Один реализованный RankingMetrics.evaluate, без повторной реализации reranker лекции.
- Зелёные новые acceptance и прежние regression/IT без изменения тестов.
- Пояснение, почему `distinct` перед cutoff завышает результат.
- Два порядка одного metadata fixture, где recall одинаков, а nDCG отличается.
- Отдельный пример NO_GOLD с объяснением null, а не «нулевое качество».
- Заполненный [SUBMISSION.md](SUBMISSION.md), происхождение наблюдений и ограничения вывода.

Не сдавайте веса, target, node_modules, личные тексты или SQL/SQLite артефакты. В metadata наблюдениях достаточно публичных document IDs и ручных grades.

## Источники

Формула DCG с экспоненциальным gain и нормализацией по идеальной выдаче описана в [Burges, From RankNet to LambdaRank to LambdaMART, §3](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/MSR-TR-2010-82.pdf). В ДЗ отдельно зафиксированы grades0..3, log2 и правило повторных chunk slots; эти условия не выдаются за универсальную обработку дубликатов.

Пример расчёта MRR как среднего вкладов RR первой релевантной позиции есть в [официальном evaluator MSMARCO](https://github.com/microsoft/MSMARCO-Passage-Ranking/blob/master/ms_marco_eval.py). У MSMARCO свои ограничения и passage IDs. Наш document-level slot contract, nullable NO_GOLD и набор grades определены выше и не копируют весь протокол benchmark.
