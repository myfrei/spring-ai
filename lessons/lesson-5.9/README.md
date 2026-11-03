# Урок 5.9. Web и консольный REPL

- [Полная пошаговая лекция](lecture/README.md).
- [Бланк эксперимента двух интерфейсов](lecture/evaluation/Interfaces-Experiment.md).
- [ДЗ: очередь внимания человека](homework/README.md).
- [Результаты проверки](VERIFICATION.md).
- [Предыдущий урок 5.8](../lesson-5.8/README.md).
- [Следующий урок 5.10](../lesson-5.10/README.md).

Страница agent.html и настоящий Java HttpClient REPL управляют одним асинхронным coordinator. Команды запуска отделены от чтения событий, есть idempotency key, cursor/replay, кооперативная отмена и exact-ID manual approvals. Подключение к ленте не выполняет tools повторно.

ДЗ добавляет AttentionQueuePolicy: приоритетную очередь pending approvals и незакрытых уведомлений. Оно развивает продукт, не повторяя event transport или реализацию клиентов.
