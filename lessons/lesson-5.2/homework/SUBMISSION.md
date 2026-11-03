# Сдача ДЗ5.2

- Автор:
- Java25 / Git / ОС:
- Коммит или patch:

## Проверки

- Готовая база:
- Восемь acceptance с -Phomework:
- Четыре fixture tests:
- RAG/UI regression:

## Свидетельства

- runId:
- Реальные readFile path/hash/start/end из /messages:
- SUPPORTED:
- NOT_READ:
- STALE после ручного изменения fixture:
- OUTSIDE_READ_RANGES с пропуском:
- UNAVAILABLE / отдельный RECEIPTS_UNAVAILABLE:
- Дополнительные поля клиента отвергнуты:

## Объяснение

1. Почему searchText/gitDiff не заменяют readFile receipt?
2. Почему нельзя объединять диапазоны разных версий или runs?
3. Почему UNAVAILABLE/STALE проверяются до покрытия диапазона?
4. Почему соседние1..4 и5..8 покрывают3..7, а1..4 и6..8 — нет?
5. Что доказывает SUPPORTED и чего он не доказывает?

## Границы

- Что изменено в policy:
- Какие изменения fixture были только частью эксперимента:
- Остались ли TODO:
- Реальные веса / HTTP fixture:
