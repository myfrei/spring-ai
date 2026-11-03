# Урок 4.1. Java tools и управляемый цикл

RepoPilot получает доступ к учебному каталогу TaskShelf через Java-инструменты `getProjectInfo` и `getTask`. Вы построите два пути работы: фиксированный workflow и цикл, в котором модель выбирает вызовы инструментов в заданных пределах.

## Материалы

| Материал | Ссылка |
| --- | --- |
| Лекция и рабочий пример | [Открыть лекцию](lecture/README.md) |
| Домашнее задание | [Условия и каркас](homework/README.md) |
| Тесты лекции | [Java](lecture/src/test/java/) · [JavaScript](lecture/src/test/js/) |
| Приёмочные тесты ДЗ | [TaskPagePolicyAcceptanceTest](homework/src/test/java/dev/myfrei/repopilot/taskpage/TaskPagePolicyAcceptanceTest.java) |

## Что вы освоите

- Описывать Java-инструменты и проверять аргументы и возвращаемые данные.
- Управлять tool loop через Spring AI и ограничивать вызовы модели, инструментов и размер контекста.
- Подтверждать успех по фактически прочитанной задаче и разбирать причины остановки цикла.

## Домашнее задание

Добавьте постраничный список задач с фильтром статуса, стабильной сортировкой и курсором, привязанным к версии каталога. Пользователь должен пройти все страницы и получить явный отказ при устаревшем курсоре.

Каталог, callback и API уже подключены. Реализуйте `TaskPagePolicy.page` по [условиям ДЗ](homework/README.md) и заполните [SUBMISSION.md](homework/SUBMISSION.md).

## Проверка

Нужны Java 25 и Node.js 24. Каждый блок запускается **отдельно из папки урока**.

Рабочий пример:

```bash
cd lecture
./mvnw test
npm ci
npm test
```

Каркас домашнего задания:

```bash
cd homework
./mvnw '-Dtest=*Test,!TaskPagePolicyAcceptanceTest' test
./mvnw -Dtest=TaskPagePolicyAcceptanceTest test
npm ci
npm test
```

После реализации ДЗ выполните `./mvnw test`. Для проверки унаследованного RAG в выбранном проекте запустите `./mvnw verify -Ppgvector-it` с работающим Docker.

Запуск workflow и модельного режима описан в [полной лекции](lecture/README.md). Workflow можно проверить без LLM; для MODEL нужна модель с поддержкой tools. [Результаты проверки примера](VERIFICATION.md).

## Дополнительная практика

Сравните WORKFLOW и MODEL в [бланке эксперимента](lecture/evaluation/Tools-Experiment.md): какие инструменты действительно вызваны, почему завершён цикл и сколько запросов понадобилось.

---

[← Урок 3.4](../lesson-3.4/README.md) · [Все уроки](../../README.md#программа) · [Урок 4.2 →](../lesson-4.2/README.md)
