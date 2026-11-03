# Проверка урока4.1

Дата: **2026-10-31**. Java25, Spring Boot4.1.2, Spring AI2.0.2. Проверены полный RepoPilot и новый read-only сценарий Java tools.

## Lecture

```bash
cd lecture
./mvnw verify -Ppgvector-it
npm ci
npm test
```

Результат: **310 Java/HTTP +35 pgvector IT +104 JS**, failures/errors/skips=0. В профиле pgvector-it используется настоящий Docker/PostgreSQL/pgvector; без него быстрые тесты не требуют Docker. Новому tools API PostgreSQL не нужен.

К273 проверкам3.4 добавлено **37**:26 unit/schema/domain/config и11 actual HTTP. Проверены:

- Фиксированный Java-каталог, public TaskView без внутренних criteria и immutable данные.
- WORKFLOW: два фактических Java-чтения, ноль model calls, структурированный результат.
- Настоящий Spring AI ToolCallingAdvisor и FunctionToolCallback через HTTP-fixture Ollama. Запросы содержат реальные tools/schema и provider options num_ctx4096/num_predict384/temperature0.
- Scope getTask: JSON Schema содержит только taskId текущего запроса; runtime повторно проверяет scope. Исходный schema/runtime mismatch исправлен и покрыт отдельными assertions для двух IDs.
- Правильный успех: три model rounds, два выполненных tools, суммарный reported usage24/9 без двойного учёта. Наличие ответа без успешного getTask отклоняется.
- Raw JSON: type/unknown/duplicate keys/trailing output, чужой taskId и неизвестный tool не запускают неразрешённое действие.
- Native manager ограничивает суммарные callback calls4; hook ограничивает model calls4. ToolCallingAdvisor2.0.2 не имеет builder.maxIterations — использован его настоящий hook API.
- Per-round heuristic budget включает сообщения, schemas, tool calls/results. Заблокированный до inference round не увеличивает modelCalls.
- Ошибка провайдера, отсутствие tool capability, незавершённый/пустой ответ и digest drift не маскируются успешным workflow. Errors содержат bounded metadata report и указание на детерминированный путь.
- Request-local run/report не смешивает конкурентные запросы. В metadata report нет question/raw args/raw results/answer; публичный API отдельно возвращает task и generated answer.

HTTP-набор запускает настоящий Spring MVC/SDK с RAG=false и недоступным PostgreSQL. LLM представлена HTTP-fixture; это не запуск весов и не оценка послушания конкретной модели.

## Homework

Всё lecture предоставлено готовым. Новое приращение — TaskPagePolicy.page: сортировка/фильтр, bounded pagination и cursor, привязанный к публичному снимку и status.

```bash
cd homework
./mvnw -DskipTests package
./mvnw '-Dtest=*Test,!TaskPagePolicyAcceptanceTest' verify -Ppgvector-it
npm ci
npm test
./mvnw -Dtest=TaskPagePolicyAcceptanceTest test
```

Каркас компилируется; база **313 Java +35 IT +104 JS** зелёная. В неё входят310 проверок lecture и три проверки предоставленного adapter. Новые **8 acceptance красные** из-за явного UnsupportedOperationException в единственном TODO.

Во временной reference-копии изменён только TaskPagePolicy.java, tests идентичны каркасу: **321 Java +35 IT +104 JS**, всё зелёное. После независимого review те же восемь acceptance усилены без изменения reference: OPEN cursor отвергает изменение DONE-строки; version совпадает для разных фильтров; validation выполняется до filter; проверяются null/duplicate и полное содержимое Row, а не только IDs. Целевой повтор11 policy/adapter-проверок зелёный. Решение находится вне курса.

Собранный JAR временного эталона дополнительно запущен с RAG=false и недоступными Ollama/PostgreSQL. Реальные GET definition и POST page вернули пять задач ровно один раз через две страницы; смена filter при старом cursor и неверный status дали sanitized400. Тестовый процесс остановлен.

## Среда и границы

Temurin25.0.5+8, Maven Wrapper3.9.11, Node24.16.0/npm11.13.0, Testcontainers2.0.5, pgvector/pgvector:0.8.1-pg17. Проверки выполнялись offline по закреплённым зависимостям; локальные HTTP/Docker-процессы запускались с разрешением loopback.

Реальные Ollama веса не запускались. Поддержку tools и качество ответа надо проверить отдельным smoke по [Tools-Experiment.md](lecture/evaluation/Tools-Experiment.md). Успешный getTask доказывает чтение данных, не истинность каждого предложения answer и не выполнение самой задачи. Tools здесь только читают, не редактируют файлы и не выполняют команды.

Token budget — эвристика Utf8TokenEstimator, а не точный tokenizer/побайтовый wire size; read-timeout120s относится к отдельным HTTP-операциям, не общему deadline всего loop. Новый JSON API не сохраняет SQLite conversation или benchmark trace автоматически.
