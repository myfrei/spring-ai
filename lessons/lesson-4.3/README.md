# Урок 4.3. Spring MCP Server и Streamable HTTP

Вы создадите отдельный Spring MCP-сервер TaskShelf с доступом по HTTP. Инструменты, ресурс и промпт будут регистрироваться аннотациями, а клиент сможет подключаться к уже работающему процессу через Streamable HTTP.

## Материалы

| Материал | Ссылка |
| --- | --- |
| Лекция и рабочий пример | [Открыть лекцию](lecture/README.md) |
| Домашнее задание | [Условия и каркас](homework/README.md) |
| Тесты лекции | [HTTP/MCP-сервер](lecture/task-http-server/src/test/java/) · [RepoPilot](lecture/src/test/java/) |
| Приёмочные тесты ДЗ | [ReleaseReadinessAcceptanceTest](homework/task-http-server/src/test/java/dev/myfrei/repopilot/readiness/ReleaseReadinessAcceptanceTest.java) |

## Что вы освоите

- Настраивать независимый Spring Boot MCP-сервер со Streamable HTTP.
- Регистрировать возможности аннотациями и задавать явные схемы аргументов.
- Проверять bearer-токен, Origin, HTTP lifecycle и протокольные ошибки через SDK и Inspector.

## Домашнее задание

Добавьте оценку готовности задачи к релизу: выберите актуальные результаты проверок, учтите последние попытки и различайте FAIL, UNKNOWN, отсутствие данных и конфликт. Вердикт READY должен опираться на полный набор подтверждений.

Реализуйте `ReleaseReadinessPolicy.assess` в `homework/task-http-server`; новый tool и adapter предоставлены. Данные `COURSE_FIXTURE` учебные, они не подтверждают запуск CI. Заполните [SUBMISSION.md](homework/SUBMISSION.md) по [условиям ДЗ](homework/README.md).

## Проверка

Нужны Java 25 и Node.js 24. Каждый блок запускается **отдельно из папки урока**. HTTP-сервер — самостоятельный Maven-проект.

Рабочий пример:

```bash
cd lecture
./mvnw test
./mvnw -f task-http-server/pom.xml verify
./mvnw -f task-server/pom.xml verify
npm ci
npm test
```

Каркас домашнего задания:

```bash
cd homework
./mvnw test
./mvnw -f task-http-server/pom.xml verify
./mvnw -f task-http-server/pom.xml -Phomework test
./mvnw -f task-server/pom.xml verify
npm ci
npm test
```

Профиль `-Phomework` включает приёмочные тесты новой функции. После решения выполните `./mvnw -f task-http-server/pom.xml -Phomework verify`. Сам MCP-сервер не требует модели или БД.

Для унаследованного RAG доступен `./mvnw verify -Ppgvector-it` с Docker. Запуск HTTP-сервера и Inspector описан в [полной лекции](lecture/README.md). [Результаты проверки примера](VERIFICATION.md).

## Дополнительная практика

Заполните [бланк HTTP/MCP-эксперимента](lecture/evaluation/Mcp-Http-Experiment.md): сравните доступ с токеном и без него, корректные вызовы и ошибки аргументов.

---

[← Урок 4.2](../lesson-4.2/README.md) · [Все уроки](../../README.md#программа) · [Урок 4.4 →](../lesson-4.4/README.md)
