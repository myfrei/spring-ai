# Урок 1.4. Conversation, память и SQLite

RepoPilot поддерживает несколько диалогов и восстанавливает историю после перезапуска. Полный журнал сохраняем в SQLite, а в запрос к модели передаём только ограниченное окно памяти.

## Материалы

| Материал | Ссылка |
| --- | --- |
| Лекция и рабочий пример | [Открыть лекцию](lecture/README.md) |
| Домашнее задание | [Условия и каркас](homework/README.md) |
| Тесты лекции | [Диалоги и SQLite](lecture/src/test/java/dev/myfrei/repopilot/conversation/) · [JavaScript и UI](lecture/src/test/js/) |
| Приёмочные тесты ДЗ | [Поиск](homework/src/test/java/dev/myfrei/repopilot/conversation/HistorySearchAcceptanceTest.java) · [HTTP API](homework/src/test/java/dev/myfrei/repopilot/conversation/HistorySearchHttpAcceptanceTest.java) |

## Что вы освоите

- Разделите диалоги по ID и сохраните вопросы, ответы и состояния попыток в SQLite.
- Отделите полную историю интерфейса от ограниченной памяти, передаваемой LLM.
- Восстановите диалог после перезапуска и корректно отобразите незавершённые попытки.

## Домашнее задание

Добавьте поиск старых вопросов и ответов через `/api/conversations/{id}/search`. Новый поиск должен работать по полному журналу выбранного диалога, учитывать Unicode и не изменять память модели.

База с SQLite и интерфейсом готова; реализуйте `HistorySearch` по [условиям задания](homework/README.md). Сценарии поиска и результаты проверок внесите в [SUBMISSION.md](homework/SUBMISSION.md).

## Проверка

Нужны JDK 25 и Node.js 24. Каждый блок выполняется независимо из папки `lesson-1.4`.

Лекционный пример:

```bash
cd lecture
./mvnw test
npm ci
npm test
```

Домашнее задание:

```bash
cd homework
./mvnw -Dtest=HistorySearchAcceptanceTest,HistorySearchHttpAcceptanceTest test
./mvnw test
npm ci
npm test
```

Приёмочные тесты поиска должны пройти после реализации ДЗ. Запуск приложения и проверка сохранения истории после перезапуска описаны в [лекции](lecture/README.md).

[Результаты проверки примера](VERIFICATION.md).

## Дополнительная практика

- Заполните [бланк контрольных вопросов](lecture/evaluation/Baseline.md) своими ответами модели, чтобы сравнить их с будущими версиями RepoPilot.
- [Пример восстановленного диалога](Preview.jpg) показывает учебные данные HTTP-стенда.

---

[← Урок 1.3](../lesson-1.3/README.md) · [Все уроки](../../README.md#программа) · [Урок 2.1 →](../lesson-2.1/README.md)
