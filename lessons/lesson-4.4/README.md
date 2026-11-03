# Урок 4.4. MCP host: remote tool → RAG → ответ

- [Полная пошаговая лекция](lecture/README.md).
- [Пустой бланк эксперимента host](lecture/evaluation/Remote-Host-Experiment.md).
- [Самостоятельное ДЗ: circuit breaker](homework/README.md).
- [Результаты проверки](VERIFICATION.md).
- [Предыдущий урок 4.3](../lesson-4.3/README.md).
- [Следующий урок5.1](../lesson-5.1/README.md).

RepoPilot становится MCP host: получает публичную задачу через HTTP-сервер 4.3, ищет документацию в опубликованном RAG-индексе и формирует ответ. Java25, Boot4.1.2, SpringAI2.0.2, SDK2.1.0; endpoint сервера `127.0.0.1:8092/mcp`, host `127.0.0.1:8080`.

WORKFLOW выбирает remote getTask в Java. MODEL имеет отдельную ограниченную фазу ToolCallingAdvisor с returnDirect. Затем оба режима выполняют детерминированный retrieval и до одного финального вызова ChatClient без tools. Автоимпорт callbacks выключен; host допускает явный квалифицированный getTask с проверкой аргументов и полного результата SDK. Connection создаётся лениво; недоступный сервер не должен ломать старт приложения. Нового UI нет.

Новый host по умолчанию выключен. Для лаборатории включите `repopilot.mcp-host.enabled=true`, сохранив включённый RAG; полный запуск сервера, Ollama и опубликованного индекса приведён в лекции. При NO_EVIDENCE финального inference нет.

В homework весь pipeline готов. Новое поведение — circuit breaker только вокруг remote gateway: CLOSED/OPEN/HALF_OPEN, cooldown, единственный probe и защита от устаревших completions. Отдельный маршрут показывает эффект policy; автоматических retries нет. Студент не переписывает MCP discovery, tool bridge или RAG.
