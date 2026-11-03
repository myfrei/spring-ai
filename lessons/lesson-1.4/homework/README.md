# ДЗ 1.4. Поиск по истории без изменения памяти модели

[Лекция](../lecture/README.md) · [Материалы урока](../README.md)

## Чем задание отличается от лекции

SQLite, диалоги, streaming, восстановление и ограниченное окно памяти здесь **уже готовы**. Новая задача — найти старый вопрос или ответ в полном журнале, не расширяя prompt и не делая найденные failed-попытки частью памяти LLM.

Предоставлен новый `GET /api/conversations/{id}/search?q=...&limit=10`. Реализуйте только [HistorySearch.find](src/main/java/dev/myfrei/repopilot/conversation/HistorySearch.java). Готовый [HistorySearchController](src/main/java/dev/myfrei/repopilot/conversation/HistorySearchController.java) читает snapshot из SQLite и передаёт его matcher. UI диалогов не нужно переписывать.

## Контракт

- query: непустая строка, исходная длина до 200 Java char. Перед поиском strip(). null/blank/слишком длинный запрос — IllegalArgumentException.
- limit: 1..20, иначе IllegalArgumentException.
- Сравнение: Unicode NFC и lower-case с Locale.ROOT для query, question и answer; буквальный substring, без regex или SQL wildcard.
- Совпадение разрешено в question **или** answer.
- Только requested conversationId и terminal statuses: completed, failed, cancelled, interrupted. pending и чужие записи исключаются.
- Сначала фильтрация, затем newest-first по числовому Turn.id, затем limit.
- Возвращается неизменяемый список исходных Turn. Входной snapshot не сортируется на месте и не изменяется.
- Поиск ничего не записывает в SQLite и не вызывает модель. Окно completed-memory остаётся прежним.

Например, `.*_%` — обычная строка, а не шаблон. Ключевое слово ПАМЯТЬ должно находить «память». Диакритики не удаляются; NFC объединяет канонически эквивалентные формы вроде cafe + combining accent.

Хранилище маленького локального проекта загружает полную историю, поэтому limit ограничивает результат, а не объём чтения из SQLite. Это не production full-text index или пагинация большого журнала.

## API и проверки

```bash
curl -sS 'http://127.0.0.1:8080/api/conversations/CONVERSATION_UUID/search?q=sqlite&limit=5'
./mvnw -DskipTests package
./mvnw -Dtest=HistorySearchAcceptanceTest,HistorySearchHttpAcceptanceTest test
./mvnw test
npm ci
npm test
```

Подставьте реальный UUID созданного диалога. Неизвестный диалог — HTTP 404; неверные параметры — 400. Успех — JSON-массив Turn, самый новый первым. Полная история и statuses сохраняются.

Новые 8 acceptance-тестов проверяют Unicode/буквальное сопоставление, изоляцию, terminal-only, порядок/limit, immutable snapshot, реальный SQLite, неизменность памяти и HTTP wiring. Базовые 79 Java и 60 JS из лекции готовы; новые поведенческие проверки сначала красные. Временный эталон проходит полный набор без изменения тестов.

## Ручная проверка

Создайте два диалога, оставьте одинаковое слово в обоих, получите один failed/cancelled partial. Ищите в одном диалоге. Убедитесь, что нет чужих и pending-строк. Затем отправьте следующий вопрос модели: search не должен добавлять найденную историю сверх штатного completed-window.

## Сдача

[SUBMISSION.md](SUBMISSION.md): тесты, пример поиска, проверка изоляции и неизменности memory window. Разделяйте сохранённый журнал, выдачу поиска и контекст модели. Усложнение — добавить документированную пагинацию с устойчивым курсором, сохранив этот API.
