# Урок 4.1. Java tools и управляемый цикл

- [Полная лекция и рабочий пример](lecture/README.md).
- [Пустой бланк эксперимента](lecture/evaluation/Tools-Experiment.md).
- [Самостоятельное ДЗ: пагинация задач](homework/README.md).
- [Фактические результаты проверки](VERIFICATION.md).
- [Предыдущий урок 3.4](../lesson-3.4/README.md).

RepoPilot получает read-only TaskCatalog и два Java tools: getProjectInfo/getTask. WORKFLOW вызывает их фиксированно без LLM; MODEL использует настоящий Spring AI ToolCallingAdvisor с границами model/tool calls и полного контекста. Успех MODEL требует реально выполненного getTask нужного ID; unsupported модель, отсутствие вызова и лимиты не маскируются успешным ответом.

Стек: Java 25, Spring Boot 4.1.2, Spring AI 2.0.2, Ollama. Новый сценарий — JSON API без дополнительной UI-панели. Каждая папка lecture/homework — самостоятельный Maven-проект.

Из lecture можно начать с workflow без векторной БД:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--repopilot.rag.enabled=false
```

Для MODEL нужен Ollama и фактически проверенная tool-capable модель. qwen2.5:3b заявляет tools; реальные веса проверяются отдельным smoke по лекции, fixtures его не заменяют.

```bash
./mvnw test
npm ci
npm test
./mvnw verify -Ppgvector-it
```

Полный унаследованный pgvector-профиль требует Docker. Подробные результаты и границы — в VERIFICATION.

В homework tool loop предоставлен готовым. Новая задача — TaskPagePolicy с фильтром, стабильным порядком, snapshot-version и cursor. Новый listTasks/page API проверяется отдельно от базового MODEL allowlist.
