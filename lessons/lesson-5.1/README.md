# Урок 5.1. AI Harness: собственный агентский цикл

- [Полная пошаговая лекция](lecture/README.md).
- [Пустой бланк наблюдений за запуском](lecture/evaluation/Agent-Run-Experiment.md).
- [Самостоятельное ДЗ: остановка при отсутствии прогресса](homework/README.md).
- [Результаты проверки](VERIFICATION.md).
- [Предыдущий урок 4.4](../lesson-4.4/README.md).
- [Следующий урок 5.2](../lesson-5.2/README.md).

Новый сценарий RepoPilot явно разделяет Agent, Conversation и Run. Java-код управляет вызовами ChatModel, проверкой tool calls, результатами инструментов, контекстом, лимитами и событиями. Используются Java 25, Boot 4.1.2 и Spring AI 2.0.2; прежние чат/RAG/MCP-сценарии сохранены, новый UI не требуется.

В Spring AI 2.x прямой ChatModel не исполняет tool loop автоматически. Удалённый флаг internalToolExecutionEnabled(false) из 1.x здесь не нужен и не компилируется. Manual runtime не использует ChatClient/ToolCallingAdvisor или скрытую память: на каждом шаге отправляет ровно подготовленный Prompt с definitions разрешённых read-only TaskShelf tools.

В homework база готова. Новая CycleStopRule ищет повторяющийся суффикс наблюдений инструментов и завершает запуск NO_PROGRESS до следующего модельного вызова. Студент не переписывает runtime, registry или tools. Skills рассматриваются отдельно в 5.4; этот урок не реализует их загрузку и не изменяет веса модели.
