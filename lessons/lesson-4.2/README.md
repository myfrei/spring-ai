# Урок 4.2. MCP Java SDK и STDIO

- [Полная пошаговая лекция](lecture/README.md).
- [Пустой бланк протокольного эксперимента](lecture/evaluation/Mcp-Stdio-Experiment.md).
- [Самостоятельное ДЗ: acceptance checklist](homework/README.md).
- [Результаты проверки двух проектов](VERIFICATION.md).
- [Предыдущий урок 4.1](../lesson-4.1/README.md).
- [Следующий урок4.3](../lesson-4.3/README.md).

В lecture сохранена полная база RepoPilot 4.1. Новый `lecture/task-server` — независимый Maven-проект: Java 25, MCP Java SDK 2.1.0, STDIO. Сервер публикует listTasks/getTask, project-overview resource и analyze_task prompt; он не запускает LLM или HTTP endpoint.

```bash
cd lecture
./mvnw -f task-server/pom.xml verify
```

Исполняемый artifact — `task-server/target/taskshelf-mcp-server.jar`. MCP-клиент запускает его как дочерний процесс; stdin/stdout предназначены для protocol, диагностический текст идёт в stderr. SDK subprocess tests и закреплённый Inspector CLI описаны в лекции. Согласованная protocol revision —2025-11-25; более новая2026-07-28 обсуждается отдельно и не подменяет реализацию SDK.

Основной Boot-проект и task-server собираются раздельно. Старый `/api/tools/model` пока использует локальные Java callbacks4.1: запуск MCP JAR не переключает его автоматически на MCP. HTTP/Spring-подключение будет отдельным шагом4.3. Новых UI-панелей нет.

В homework STDIO registration/lifecycle уже готовы. Новая policy строит ограниченный acceptance checklist с нормализацией, стабильными AC IDs и честными признаками потерь; отдельный ChecklistServerMain добавляет getAcceptanceChecklist третьим tool. Базовый сервер остаётся с двумя tools.
