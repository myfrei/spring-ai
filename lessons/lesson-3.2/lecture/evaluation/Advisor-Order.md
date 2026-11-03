# Порядок и контракт advisors

Это бланк наблюдений, не заполненный отчёт качества модели.

- Дата, Java/Spring AI, model tag/digest:
- CorpusVersion / fingerprint:
- Вопрос и пустая/явная history:
- Outbound prompt hash и оценка input budget:

| Вариант | order | before/after порядок | Retrieval calls | Model calls | Terminal/outcome | Вывод |
| --- | --- | --- | --- | --- | --- | --- |
| Input -100, RAG 0 | | | | | | |
| Обратная регистрация | | | | | | |
| Input 100, RAG 0 | | | | | | |
| Два RAG advisor | | | | | | |
| Prepared snapshot | | | | | | |

Отделите один stream с несколькими delta от повторной подписки на Flux. Отметьте, где использовали RecordingModel/HTTP fixture, а где настоящие веса.
