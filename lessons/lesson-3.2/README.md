# Урок 3.2. ChatClientRequest и собственный Advisor

RepoPilot получает собственный RAG Advisor: подготовка источников и добавление контекста становятся частью цепочки обработки запроса. Вы сохраните потоковый чат и добавите отдельный синхронный API с отчётом об использованных источниках.

## Материалы

| Материал | Ссылка |
| --- | --- |
| Лекция и рабочий пример | [Открыть лекцию](lecture/README.md) |
| Домашнее задание | [Условия и каркас](homework/README.md) |
| Тесты лекции | [Java](lecture/src/test/java/) · [JavaScript](lecture/src/test/js/) |
| Приёмочные тесты ДЗ | [DocumentScopeAcceptanceTest](homework/src/test/java/dev/myfrei/repopilot/advisor/DocumentScopeAcceptanceTest.java) |

## Что вы освоите

- Различать сообщения модели и служебный контекст `ChatClientRequest`.
- Создавать Advisor для готового RAG-плана и для подготовки источников во время запроса.
- Проверять порядок Advisors, работу call/stream и однократное добавление контекста.

## Домашнее задание

Добавьте выбор тематического профиля источников: для диагностики используйте runbook, для архитектуры — документы overview и runtime. Отбор должен происходить до расчёта бюджета контекста. В prompt и отчёт должны попадать только разрешённые профилем источники.

Цепочка Advisors уже готова. Реализуйте `DocumentScope.select` по [условиям ДЗ](homework/README.md) и заполните [SUBMISSION.md](homework/SUBMISSION.md).

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
./mvnw '-Dtest=*Test,!DocumentScopeAcceptanceTest' test
./mvnw -Dtest=DocumentScopeAcceptanceTest test
npm ci
npm test
```

После реализации ДЗ выполните `./mvnw test`. Для проверки полного RAG в выбранном проекте запустите `./mvnw verify -Ppgvector-it` с работающим Docker.

Запуск с Ollama и опубликованным индексом описан в [полной лекции](lecture/README.md). [Результаты проверки примера](VERIFICATION.md).

## Дополнительная практика

Исследуйте [порядок Advisors](lecture/evaluation/Advisor-Order.md), затем сравните источники и ответы для разных профилей в [бланке ДЗ](homework/evaluation/Scope-Decision.md).

---

[← Урок 3.1](../lesson-3.1/README.md) · [Все уроки](../../README.md#программа) · [Урок 3.3 →](../lesson-3.3/README.md)
