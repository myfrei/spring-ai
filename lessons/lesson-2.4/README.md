# Урок 2.4. Сравнение CHAT, FULL_CONTEXT и RAG

Проверим три стратегии на одинаковых вопросах, чтобы выбирать подход по результатам эксперимента. RepoPilot сохранит ответы, источники, задержки и ручные оценки, а затем соберёт отчёт в Markdown.

## Материалы

| Материал | Ссылка |
| --- | --- |
| Лекция и рабочий пример | [Открыть лекцию](lecture/README.md) |
| Домашнее задание | [Условия и каркас](homework/README.md) |
| Тесты лекции | [Эксперименты и отчёты](lecture/src/test/java/dev/myfrei/repopilot/evaluation/) · [JavaScript и UI](lecture/src/test/js/) |
| Приёмочные тесты ДЗ | [Сравнение двух прогонов](homework/src/test/java/dev/myfrei/repopilot/homework/PairedRegressionHomeworkAcceptanceTest.java) |

## Что вы освоите

- Проведёте сравнение CHAT, FULL_CONTEXT и RAG с повторениями и фиксированными условиями.
- Отдельно учтёте задержки, ошибки, отсутствие релевантных источников и ручную оценку ответов.
- Разделите DEV и HOLDOUT и оформите решение о выборе стратегии на основе своего эксперимента.

## Домашнее задание

Средняя оценка может вырасти, хотя отдельный ответ стал хуже. Реализуйте `PairedRunComparator`: сопоставляйте два прогона по вопросу и повторению и выявляйте регрессии качества ответа или цитирования.

Проведение экспериментов уже готово. Следуйте [контракту задания](homework/README.md), затем заполните [SUBMISSION.md](homework/SUBMISSION.md), объяснив найденные различия и ограничения сравнения.

## Проверка

Нужны JDK 25 и Node.js 24. Каждый блок выполняется независимо из папки `lesson-2.4`.

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
./mvnw -Dtest='*Test,!*HomeworkAcceptanceTest' test
./mvnw -Dtest=PairedRegressionHomeworkAcceptanceTest test
npm ci
npm test
```

Новые приёмочные проверки должны пройти после реализации сравнения. Подготовка моделей, pgvector и дополнительные SQL-проверки с Docker описаны в [лекции](lecture/README.md).

[Результаты проверки примера](VERIFICATION.md).

## Дополнительная практика

- Заполните [бланк выбора стратегии](lecture/evaluation/Strategy-Decision.md) своими измерениями.
- Используйте [учебный набор вопросов DEV/HOLDOUT](lecture/src/main/resources/evaluation/cases.json) по правилам эксперимента.
- [Пример панели сравнения](Preview.jpg) показывает учебные данные HTTP-стенда.

---

[← Урок 2.3](../lesson-2.3/README.md) · [Все уроки](../../README.md#программа) · [Урок 3.1 →](../lesson-3.1/README.md)
