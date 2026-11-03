# ДЗ 4.1. Страницы списка задач

[Лекция](../lecture/README.md) · [Материалы урока](../README.md) · [Результаты проверки](../VERIFICATION.md)

Вся база лекции готова: каталог TaskShelf, workflow, настоящий Spring AI tool loop, его лимиты, audit и проверки. Новая функция — **постраничный read-only listTasks** с курсором, привязанным к данным и фильтру. В лекции getProjectInfo/getTask не реализуют этот контракт. Повторить их код для зачёта недостаточно.

Заполните только [TaskPagePolicy.page](src/main/java/dev/myfrei/repopilot/taskpage/TaskPagePolicy.java). Callback, строгий JSON adapter, API, cursor codec, DTO и тесты предоставлены.

## Что уже подключено

- `GET /api/tools/tasks/definition` показывает определение listTasks и его JSON Schema.
- `POST /api/tools/tasks/page` детерминированно вызывает новый TaskPageTool, без LLM/SQL.
- TaskPageTool получает публичную проекцию каталога: id/title/status. В неё не включаются summary или внутренние criteria.
- Callback намеренно создаётся локально контроллером, не является глобальным ToolCallback bean и не добавлен к инструментам базового `/api/tools/model`. Поэтому новая незавершённая policy не ломает модельный сценарий лекции и не расширяет его allowlist.
- Endpoint включён при `repopilot.tools.enabled=true`, как и основа лекции. RAG/PostgreSQL ему не нужны.

Это подготовленный Java ToolCallback: определение и выполнение уже связаны. Добавлять его в автоматический модельный сценарий необязательно; там потребуется отдельно спроектировать выбор набора tools и правило завершения, которое в текущей лекции требует getTask.

## Контракт одного TODO

```java
TaskPage page(List<TaskPage.Row> rows, String status, int limit, String cursor)
```

Row: `id`, `title`, `status`; допустимые статусы строки — OPEN, IN_PROGRESS, DONE. ID имеет формат TASK-101. Immutable Row и Page предоставлены.

1. Входной список обязателен, не более100 строк, null-строки и повтор одного id запрещены. Не изменяйте вход.
2. status: ровно ALL/OPEN/IN_PROGRESS/DONE; limit — целое1..5.
3. Сортируйте всю публичную проекцию по id по возрастанию. Затем применяйте фильтр; ALL оставляет все строки.
4. total — число строк после фильтра, а не размер всего каталога. returned — число строк текущей страницы.
5. Версия снимка вычисляется готовым `TaskPageCursor.version(sortedAllRows)` **до фильтра**. В hash входят только id/title/status; summary и criteria в этой проекции отсутствуют.
6. Для первой страницы cursor равен null/пустой строке. Готовый `TaskPageCursor.decode(cursor,version,status,total)` возвращает offset либо отвергает неверный/устаревший cursor.
7. Верните не более limit строк начиная с offset. Следующий cursor — `encode(version,status,nextOffset)` только если строки ещё остались; иначе **пустая строка**, не null.
8. Результат — immutable snapshot; перестановка исходных строк не меняет version и страницы.

Изменение id/title/status любой строки делает cursor устаревшим. Его нельзя использовать с другим status. Между страницами разрешено менять limit. Cursor привязывает позицию к снимку, но не является токеном авторизации. Здесь читается только вымышленный публичный каталог одного проекта.

Codec уже реализован: Base64URL от `version|status|offset`, offset должен находиться внутри отфильтрованного списка. Не переписывайте codec и не отключайте проверки для прохождения тестов. Размер output ограничен пятью строками с ограниченным title; это не точный лимит model tokens.

## Запуск

Из homework, Java25:

```bash
./mvnw -DskipTests package
./mvnw '-Dtest=*Test,!TaskPagePolicyAcceptanceTest' test
npm ci
npm test
./mvnw -Dtest=TaskPagePolicyAcceptanceTest test
```

Каркас компилируется. Базовые regression и три проверки готового adapter должны быть зелёными. Восемь новых acceptance сначала красные из-за явного TODO. После реализации:

```bash
./mvnw test
./mvnw verify -Ppgvector-it
npm test
```

Последний Maven-профиль проверяет унаследованный RAG и требует Docker; сама pagination не использует ни Docker, ни Ollama. Фактические счётчики — в VERIFICATION.

Для ручной проверки запускайте обычный проект по лекции. Если нужен только Java tools путь без PostgreSQL:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments='--repopilot.rag.enabled=false'
curl -sS http://127.0.0.1:8080/api/tools/tasks/definition
curl -sS http://127.0.0.1:8080/api/tools/tasks/page \
  -H 'Content-Type: application/json' \
  -d '{"status":"ALL","limit":2}'
```

Скопируйте nextCursor в следующий запрос:

```bash
curl -sS http://127.0.0.1:8080/api/tools/tasks/page \
  -H 'Content-Type: application/json' \
  -d '{"status":"ALL","limit":2,"cursor":"ВСТАВЬТЕ_ПОЛУЧЕННЫЙ_CURSOR"}'
```

Не передавайте placeholder буквально. Проверьте все страницы, смену limit, другой status и stale cursor после изменения публичных полей исходной записи. HTTP adapter не преобразует `"2"` или2.5 в integer; unknown/duplicate keys и trailing JSON отвергаются до student policy. Ошибка возвращает400 INVALID_TASK_PAGE без отражения входного payload.

## Приёмка и сдача

Восемь acceptance проверяют порядок, полный обход, фильтр/total, привязку cursor, перестановку входа, пустой/последний ответ, границы/duplicate IDs и immutable snapshot. Тесты предоставленного adapter дополнительно проверяют схему и строгий JSON.

Заполните [SUBMISSION.md](SUBMISSION.md): команды, результаты, cursor-сценарии и объяснение отличий от модели лекции. Не меняйте тесты и не подставляйте готовый массив только для TASK-101..105. Временный эталон использовался для проверки исполнимости задания и не входит в homework.
