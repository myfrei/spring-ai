# Эксперимент: FAIL → patch → PASS

Заполните наблюдениями настоящей локальной модели и Docker runner. Фикстуры не заменяют этот эксперимент.

- Дата / компьютер / Java / Docker:
- Модель / digest / окно контекста:
- Image ID и command:
- Conversation ID:
- Исходный input revision / request ID / expiresAt:
- Первый approval и report ID:
- Первый status / exit / reason / timestamps:
- Какой contract check не прошёл:
- Agent run / plan / model calls / tool calls:
- Прочитанные путь, hash и строки:
- Предложенный diff / proposal ID:
- Решение по patch:
- Новый input revision / request / approval:
- Финальный report / status / exit / reason:
- Typed verification state:

| Сценарий | Ожидание | Факт | Доказательство |
| --- | --- | --- | --- |
| Ошибочная версия | FAIL / exit10 | | |
| Pending или REJECT | Ноль исполнений | | |
| Старое approval после edit | VERIFICATION_STALE | | |
| Повтор APPROVE | Отказ без запуска | | |
| Исправленная новая версия | PASS / exit0 | | |
| Изменение после проверки | Старый отчёт исторический, live summary STALE | | |

Какие именно свойства проверены:

Что проверка не подтверждает:

Что потребовало ревью человеком:
