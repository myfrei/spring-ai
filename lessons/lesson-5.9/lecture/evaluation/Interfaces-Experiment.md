# Эксперимент: Web и REPL наблюдают одну задачу

Заполните реальными наблюдениями. Fixture results и real-model results указывайте отдельно.

- Дата / компьютер / Java / модель:
- Conversation ID / requestId / session ID / run ID:
- Исходный вопрос и plan:
- Model/tool calls:
- Состояние и stopReason:
- Typed verification state:

| Сценарий | Ожидание | Факт | Доказательство |
| --- | --- | --- | --- |
| POST запуска | Возврат ID до завершения LLM | | |
| Web → REPL attach | Один и тот же run | | |
| Disconnect | Task продолжает выполняться | | |
| Reconnect | GET replay, без нового исполнения | | |
| Expired cursor | Snapshot recovery, без resubmit | | |
| Explicit cancel | Кооперативная остановка | | |
| Preview | Никаких решений автоматически | | |
| Exact-ID approval | Одно применение, повтор отклонён | | |

Наблюдавшиеся sequence/cursor:

Фактические действия и результаты:

Что не удалось проверить:

Ограничение текущей реализации:
