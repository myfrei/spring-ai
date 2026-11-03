# Урок 4.4. MCP host: инструмент → RAG → ответ

RepoPilot становится MCP host: получает задачу от HTTP-сервера TaskShelf, находит документацию через RAG и формирует ответ. Вы объедините эти этапы в один сценарий с проверяемым результатом каждой фазы.

## Материалы

| Материал | Ссылка |
| --- | --- |
| Лекция и рабочий пример | [Открыть лекцию](lecture/README.md) |
| Домашнее задание | [Условия и каркас](homework/README.md) |
| Тесты лекции | [MCP host](lecture/src/test/java/dev/myfrei/repopilot/mcphost/) · [HTTP-сервер](lecture/task-http-server/src/test/java/) |
| Приёмочные тесты ДЗ | [CircuitGateAcceptanceTest](homework/src/test/java/dev/myfrei/repopilot/circuit/CircuitGateAcceptanceTest.java) |

## Что вы освоите

- Подключать удалённый MCP-инструмент с проверкой discovery, аргументов и результата.
- Разделять выбор инструмента, поиск источников и финальную генерацию в WORKFLOW и MODEL.
- Читать отчёт по фазам и различать недоступность сервера, ошибку данных и отсутствие релевантных источников в RAG.

## Домашнее задание

Добавьте circuit breaker для удалённого gateway: временно прекращайте обращения после сетевых сбоев и допускайте одну проверку восстановления. Учтите конкурентные запросы, одноразовые разрешения и поздние результаты старых обращений.

MCP-клиент и RAG уже работают. Реализуйте `CircuitGate.acquire` и `complete` по [условиям ДЗ](homework/README.md), затем опишите сценарий восстановления в [SUBMISSION.md](homework/SUBMISSION.md).

## Проверка

Нужны Java 25 и Node.js 24. Каждый блок запускается **отдельно из папки урока**.

Рабочий пример:

```bash
cd lecture
./mvnw test
./mvnw -f task-http-server/pom.xml verify
npm ci
npm test
```

Каркас домашнего задания:

```bash
cd homework
./mvnw test
./mvnw -Phomework test
npm ci
npm test
```

Профиль `-Phomework` включает приёмочные тесты новой функции. После решения выполните `./mvnw -Phomework verify`. Для полного RAG в выбранном проекте нужен Docker и `./mvnw verify -Ppgvector-it`.

Составной MCP/RAG-сценарий проверяется профилем `mcp-host-it`, восстановление gateway в ДЗ — `circuit-it`. Сборка серверного JAR и необходимые параметры приведены в [лекции](lecture/README.md) и [ДЗ](homework/README.md).

Запуск с HTTP-сервером, Ollama и опубликованным индексом описан в [полной лекции](lecture/README.md). [Результаты проверки примера](VERIFICATION.md).

## Дополнительная практика

Заполните [бланк эксперимента host](lecture/evaluation/Remote-Host-Experiment.md): проследите путь от задачи до ответа и отдельно проверьте недоступность сервера и отсутствие источников.

---

[← Урок 4.3](../lesson-4.3/README.md) · [Все уроки](../../README.md#программа) · [Урок 5.1 →](../lesson-5.1/README.md)
