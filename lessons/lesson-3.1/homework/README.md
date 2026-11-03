# ДЗ 3.1. Очередь ручного разбора

В лекции построены trace и правила диагностики. Здесь они предоставлены **готовыми**. Новая задача — собрать приоритетную очередь инцидентов, сгруппировав повторы. Код классификатора из лекции не решает это задание.

Новый API уже подключён: `GET /api/diagnostics/runs/{id}/triage?limit=10`. Реализуйте только `triage/TriageQueue.build`; базовый проект и regression tests менять не нужно.

Контракт:

1. limit от 1 до 20, иначе IllegalArgumentException.
2. Исключить NO_DETECTED_ISSUE и BASELINE_WITHOUT_DOCUMENTS.
3. Группировать по caseId + mode + category; повторы одной проблемы объединяются, разные причины и режимы остаются отдельными.
4. ordinals внутри группы отсортировать по возрастанию.
5. Приоритет 0: EXECUTION_FAILURE, INCOMPATIBLE_BASELINE, INCONSISTENT_EVIDENCE; 1: RETRIEVAL_MISS, CONTEXT_DROPPED, DATA_OR_CONTEXT_REVIEW; 2: GENERATION_REVIEW, CITATION_REVIEW, UNSUPPORTED_ANSWER_REVIEW; 3: MANUAL_REVIEW_NEEDED.
6. Сортировка: priority, caseId, mode.name, category.name. Применить limit **после** группировки.
7. totalGroups и totalRows относятся ко всем подходящим группам/строкам до limit. Результат immutable, вход не изменяется.

Это объявленная политика очереди, не оценка уверенности или качества LLM. После реализации разберите два реальных провала по бланку `../lecture/evaluation/Failure-Investigation.md`. Если настоящей модели не было, не выдумывайте измерения: отдельно сдайте fixture-разбор и отметьте ограничение.

Проверки из homework:

```bash
./mvnw -DskipTests package
./mvnw -Dtest='*Test,!TriageQueueAcceptanceTest' test
./mvnw -Dtest=TriageQueueAcceptanceTest test
./mvnw verify -Ppgvector-it
npm ci
npm test
```

Сначала база должна быть зелёной, семь новых acceptance-проверок — красными. Итоговый полный набор проходит на временном эталоне без изменения тестов. Не нужно заново писать SourceMarks, DiagnosticRules или renderer лекции.

Критерии сдачи: зелёные проверки, демонстрация limit после группировки, проверка независимости порядка от входа и два разбора «факт → гипотеза → одно изменение → повтор → вывод». Не сдавайте веса, node_modules, target, личный корпус и SQLite-переписки.
