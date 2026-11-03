# Урок 4.3. Spring MCP server и Streamable HTTP

- [Полная пошаговая лекция](lecture/README.md).
- [Пустой бланк HTTP/MCP-эксперимента](lecture/evaluation/Mcp-Http-Experiment.md).
- [Самостоятельное ДЗ: готовность релиза](homework/README.md).
- [Результаты проверки](VERIFICATION.md).
- [Предыдущий урок 4.2](../lesson-4.2/README.md).
- [Следующий урок 4.4](../lesson-4.4/README.md).

В lecture сохранена база RepoPilot 4.2. Новый `lecture/task-http-server` — независимый Spring Boot проект на Java 25, Boot 4.1.2 и Spring AI 2.0.2. Он публикует учебные listTasks/getTask, resource и prompt через Streamable HTTP `http://127.0.0.1:8092/mcp`. Модель и векторная БД для нового сервера не нужны. Protocol revision —2025-11-25, SDK —2.1.0, Inspector —0.21.2.

Это следующий шаг после STDIO: отдельный lifecycle сервера, Spring MCP-аннотации, явные schemas, demo bearer и Origin allowlist. Loopback/bearer/Origin решают разные задачи; пример не заменяет production authentication. Нового UI нет, протокол проверяем SDK/Inspector. Подключение к генеративному host вынесено в 4.4.

```bash
cd lecture
./mvnw -f task-http-server/pom.xml verify
```

В homework сервер, transport, аннотации и регистрация нового getReleaseReadiness уже предоставлены. Студент реализует отдельную ReleaseReadinessPolicy: выбирает актуальные результаты проверок, различает FAIL/MISSING/UNKNOWN/CONFLICT и строит честный verdict. Данные COURSE_FIXTURE не являются доказательством запуска CI. Новые acceptance tests проверяют policy; повторять сервер лекции не требуется.
