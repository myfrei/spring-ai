# Stock Room capstone task MCP server

Отдельное Spring Boot 4.1.2 / Spring AI 2.0.2 приложение на Java 25. Streamable HTTP слушает `127.0.0.1:8093/mcp`; MCP SDK курса согласует protocol `2025-11-25`.

Один read-only tool `getStockRoomTask` принимает ровно `{"taskId":"STOCK-001"}`. Ответ — закреплённый manifest Stock Room: источник, строки, baseline SHA-256, фиксированная команда и пять проверяемых условий. Сервер не вызывает LLM, не исполняет команды и не выдаёт разрешения.

```bash
./mvnw verify
java -jar target/stockroom-capstone-task-server-0.0.1-SNAPSHOT.jar
```

`STOCKROOM_MCP_TOKEN` задаёт общий bearer token. По умолчанию используется открыто опубликованное учебное значение `stockroom-local-course-token`; это локальная демонстрация, не production OAuth. Host и сервер должны использовать одинаковую настройку. Все маршруты защищены фильтром, чужой браузерный Origin отклоняется.

Два unit-теста и два integration-теста проверяют настоящий SDK initialize/tools-list/tools-call, закрытые аргументы, 401 и 403. Это самостоятельный сервер для Stock Room. Старые TaskShelf HTTP/STDIO приложения и их контракты не меняются.
