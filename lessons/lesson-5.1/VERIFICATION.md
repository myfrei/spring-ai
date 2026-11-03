# Проверка урока 5.1

Дата:2026-10-31. Java25.0.5+8, Maven Wrapper3.9.11, Boot4.1.2, SpringAI2.0.2, Node24.16.0/npm11.13.0. PostgreSQL/pgvector проверялся в Docker; новые model tests используют настоящий OllamaChatModel и управляемый HTTP-ответ провайдера.

## Матрица

| Срез | Проверки | Результат |
| --- | --- | --- |
| lecture | 370 Java +39 pgvector IT +104 JS | PASS |
| homework, готовая база | 373 Java +39 pgvector IT +104 JS | PASS |
| Новые CycleStopRule acceptance в каркасе | 8 ERROR из-за одного TODO | Ожидаемый RED |
| Временный эталон homework, -Phomework | 381 Java +39 pgvector IT | PASS |
| Унаследованный MCP host, lecture | 4 IT с настоящим MCP server JAR | PASS |
| Новый guarded route, private starter/reference JARs | Настоящий REST +Ollama HTTP fixture | PASS |

370 включает339 прежних проверок и31 новую: engine13, contracts9, production offline boot1, HTTP integration8. Три предоставленных GuardedAgentAdapterTest проходят до заполнения TODO. Восемь acceptance выключены только default Maven selection, а не аннотациями skip. В course/homework остаётся один незаполненный repeatedSuffix; эталон хранится отдельно и отличается только CycleStopRule.java.

## Повторение

Из lecture или homework:

```bash
./mvnw verify -Ppgvector-it
npm ci
npm test
```

Из homework до решения ожидается RED:

```bash
./mvnw -Phomework -Dtest=CycleStopRuleAcceptanceTest test
```

После решения:

```bash
./mvnw verify -Phomework,pgvector-it
```

pgvector-it требует Docker. Сам агентский цикл/правило/HTTP fixtures не требуют установленных моделей или PostgreSQL. На пустом Maven/npm cache сначала понадобится скачать закреплённые зависимости; offline подходит только для заполненного cache.

Сохранённый MCP-путь проверяется отдельно, без объединения IT-профилей:

```bash
./mvnw -f task-http-server/pom.xml -DskipTests package
./mvnw verify -Pmcp-host-it \
  -Dtaskshelf.http.jar="$PWD/task-http-server/target/taskshelf-mcp-http-server-0.0.1-SNAPSHOT.jar"
```

Этот профиль требует Docker и заранее собранный JAR. STDIO и HTTP server-проекты остаются независимыми Maven-проектами; основные app builds их автоматически не собирают. Их исходные контракты совпадают с4.4.

## Что подтверждено для ручного runtime

- Прямой ChatModel2.0.2 делает один запрос за шаг. Удалённый1.x флаг internalToolExecutionEnabled отсутствует; ChatClient/advisors/memory не вызываются из этого пути. Schema-only callbacks при попытке исполнения бросают ошибку.
- Настоящий Ollama HTTP-сценарий getProjectInfo →getTask →final сделал3 chat requests,2 фактических tools и6 digest lookups. Usage24/9 соответствует трём ответам8/3. Каждый provider prompt имеет4096/384/temperature0.
- На каждом шаге budget включает tool definitions, assistant calls/arguments и tool responses. Старые завершённые turns удаляются целиком; scratch текущего run не обрезается посреди call/result.
- IDs/type/names/closed scalar arguments проверяются до исполнения. Duplicate IDs, forbidden tool, несовместимые args и невалидный provider result не превращаются в успешный ответ.
- Лимиты6 model calls и8 tool requests обеспечивает Java-код. При остановке внутри admissible batch оставшиеся calls получают correlated NOT_EXECUTED без исполнения. Невалидный/oversized batch не добавляется как пригодный assistant message.
- Provider arguments100KiB отвергаются до raw journal/hash; результат ограничен4096 UTF-8 bytes полного envelope. Final visible text ограничен8192 bytes. Это ограничения данных после получения provider response, а не транспортный streaming byte limit.
- Event/report metadata не содержит question, args или result text. ID хранится как callIdHash, неизвестный model tool name — UNKNOWN. Raw IDs/arguments/results доступны только намеренному /messages; этот endpoint не обезличен.
- Один active run на conversation; конкурирующий старт отклоняется AGENT_BUSY. STOPPED run освобождает conversation, сохраняет journal, но не коммитит scratch в историю. Только COMPLETED turns попадают в bounded history.
- Digest проверяется до/после каждого фактического model call, включая provider error, и между calls. Usage остаётся неизвестным при неполном ответе. Нет автоматических повторов и вымышленных финальных ответов после control stop.
- Production-приложение запускается с включённым агентом и недоступным Ollama; создания conversation недостаточно для inference. Новый feature выключен по умолчанию и не зависит от готовности MCP/RAG.

## Настоящая проверка нового ДЗ через REST

Отдельные private starter/reference проекты собраны в executable Boot JAR. Они отличаются только rule; course source и основные targets проверкой не менялись. Fixture возвращала один и тот же getProjectInfo с разными call IDs.

| Запрос | Реальный результат | Chat/tools/results |
| --- | --- | --- |
| Reference, обычный /runs | STOPPED/MODEL_LIMIT |6/6/6 |
| Reference, /guarded-runs | STOPPED/NO_PROGRESS |2/2/2 |
| Исходный TODO, /guarded-runs | STOPPED/STOP_RULE_FAILED |1/1/1 |

GET /messages подтвердил уникальные IDs и результат для каждого admissible call; /events — правильные hashes без raw IDs/вопроса/result payload. После каждого run conversation освобождалась, committed turns отсутствовали. Guarded prompt не включал scratch предыдущего остановленного baseline run. Всего fixture получила9 chat requests и18 digest requests; body capture согласован с report.

Эта проверка доказывает интеграцию rule с настоящим REST/ChatModel/provider path. Восемь acceptance отдельно доказывают suffix periods1..3, всю tuple equality, ошибки, границы и отсутствие мутации. Снижение с6 до2 относится к этому контролируемому повтору, а не к замеру качества/скорости произвольной модели.

## Границы

AgentStore находится в RAM:64 conversations,256 runs,4 committed turns; перезапуск очищает его. Runtime пока синхронный, без отмены, Skills, write tools и общего wall-clock deadline. Provider timeout и счётчики шагов — разные ограничения.

COMPLETED подтверждает завершённый model protocol, но не корректность решения. Real Ollama weights/tool competence/русский язык здесь не оценивались. Для них заполните [бланк](lecture/evaluation/Agent-Run-Experiment.md). Ответы HTTP-фикстуры не выдаются за измеренное качество модели.
