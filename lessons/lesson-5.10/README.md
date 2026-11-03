# Урок 5.10. Финальная задача: MCP → исправление → проверка

Вы пройдёте полный сценарий Stock Room: получите задачу через MCP, воспроизведёте ошибку, подготовите правку и подтвердите результат новой Docker-проверкой. Применение patch и исполнение проверки требуют отдельных решений человека.

Финал проверяет фиксированный контракт StockLedger: резервирование товара и обработку граничных случаев.

## Материалы

| Материал | Ссылка |
| --- | --- |
| Лекция и рабочий пример | [Открыть лекцию](lecture/README.md) |
| Домашнее задание | [Условия и каркас](homework/README.md) |
| Тесты лекции | [Проверки новой возможности](lecture/src/test/java/dev/myfrei/repopilot/capstone/) · [JavaScript](lecture/src/test/js/) · [MCP-сервер](lecture/capstone-task-server/src/test/java/) |
| Приёмочные тесты ДЗ | [PortableAuditPolicyAcceptanceTest](homework/src/test/java/dev/myfrei/repopilot/auditexport/PortableAuditPolicyAcceptanceTest.java) |

## Что вы освоите

- Связывать импорт MCP-задачи, Skill, план и чтения кода в одном сценарии.
- Проходить путь от исходного FAIL через одобренную правку к актуальному PASS.
- Оценивать итоговый серверный отчёт с версиями и состояниями DONE, INCOMPLETE и STALE.

## Домашнее задание

Добавьте скачиваемый JSON-пакет аудита для ревью: заданный набор полей, отметки о недостающих данных и контрольную сумму. Соблюдайте бюджет полного UTF-8 документа. Пакет сохраняет исходный статус задачи и позволяет проверить целостность экспортируемых данных.

Условия и критерии приёмки — в [README задания](homework/README.md). Результат оформите в [SUBMISSION.md](homework/SUBMISSION.md).

## Проверка

Оба блока выполняйте отдельно, начиная из папки `lesson-5.10`. Подготовка среды описана в лекции.

Базовые Java- и UI-проверки рабочего примера:

```bash
cd lecture
./mvnw test
./mvnw -f capstone-task-server/pom.xml verify
npm ci
npm test
```

Базовые и приёмочные проверки домашнего задания:

```bash
cd homework
./mvnw test
./mvnw -f capstone-task-server/pom.xml verify
./mvnw -Phomework verify
npm ci
npm test
```

Приёмочные тесты описывают новую функцию и начнут проходить после её реализации. Полный сценарий с отдельным MCP-сервером и Docker выполняйте по [инструкции лекции](lecture/README.md).

[Результаты проверки примера](VERIFICATION.md).

## Дополнительная практика

- [Финальный сценарий Stock Room](lecture/evaluation/Capstone-Experiment.md).
- [Одна задача через Web и REPL](lecture/evaluation/Interfaces-Experiment.md).

---

[← Урок 5.9](../lesson-5.9/README.md) · [Все уроки](../../README.md#программа) · Конец курса
