# Урок 4.2. MCP Java SDK и STDIO

Вы вынесете каталог TaskShelf в отдельный MCP-сервер, который клиент запускает как дочерний Java-процесс. Сервер предоставит инструменты чтения задач, ресурс с описанием проекта и шаблон промпта; его можно будет исследовать через MCP Inspector.

## Материалы

| Материал | Ссылка |
| --- | --- |
| Лекция и рабочий пример | [Открыть лекцию](lecture/README.md) |
| Домашнее задание | [Условия и каркас](homework/README.md) |
| Тесты лекции | [MCP-сервер](lecture/task-server/src/test/java/) · [RepoPilot](lecture/src/test/java/) |
| Приёмочные тесты ДЗ | [AcceptanceChecklistAcceptanceTest](homework/task-server/src/test/java/dev/myfrei/repopilot/checklist/AcceptanceChecklistAcceptanceTest.java) |

## Что вы освоите

- Различать роли host/client/server и возможности tools, resources и prompts.
- Создавать MCP-сервер на Java SDK и разделять протокол STDIO и диагностический вывод.
- Проверять lifecycle, схемы и ошибки настоящим SDK-клиентом и Inspector.

## Домашнее задание

Добавьте `getAcceptanceChecklist`: выдавайте целые критерии приёмки задачи с ограничением количества и Unicode-объёма. Сохраняйте стабильные идентификаторы и явно показывайте, какие критерии не поместились.

Реализуйте `AcceptanceChecklistPolicy.build` внутри `homework/task-server`. Предоставленный `ChecklistServerMain` включает новый tool; обычный сервер сохраняет исходные инструменты. Подробности — в [ДЗ](homework/README.md), отчёт — в [SUBMISSION.md](homework/SUBMISSION.md).

## Проверка

Нужны Java 25 и Node.js 24. Каждый блок запускается **отдельно из папки урока**. Основное приложение и `task-server` собираются раздельно.

Рабочий пример:

```bash
cd lecture
./mvnw test
./mvnw -f task-server/pom.xml verify
npm ci
npm test
```

Каркас домашнего задания:

```bash
cd homework
./mvnw test
./mvnw -f task-server/pom.xml '-Dtest=*Test,!AcceptanceChecklistAcceptanceTest' verify
./mvnw -f task-server/pom.xml -Dtest=AcceptanceChecklistAcceptanceTest test
npm ci
npm test
```

После реализации ДЗ выполните `./mvnw -f task-server/pom.xml verify`. Проверки STDIO запускают настоящий дочерний процесс и не требуют Ollama или Docker.

Для унаследованного RAG доступен `./mvnw verify -Ppgvector-it` с Docker. Команды Inspector и выбор entrypoint ДЗ приведены в [лекции](lecture/README.md) и [задании](homework/README.md). [Результаты проверки примера](VERIFICATION.md).

## Дополнительная практика

Заполните [бланк протокольного эксперимента](lecture/evaluation/Mcp-Stdio-Experiment.md): проверьте discovery, успешное чтение и отказ на неверный аргумент.

---

[← Урок 4.1](../lesson-4.1/README.md) · [Все уроки](../../README.md#программа) · [Урок 4.3 →](../lesson-4.3/README.md)
