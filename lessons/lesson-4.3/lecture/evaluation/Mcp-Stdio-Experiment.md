# Бланк эксперимента: TaskShelf MCP STDIO

Результаты не заполнены. [Лекция](../README.md) · [ДЗ](../../homework/README.md) · [Проверка](../../VERIFICATION.md).

## 1. Среда и границы

| Поле | Фактическое значение |
| --- | --- |
| Дата / ОС / JDK | |
| Git revision / server JAR | |
| MCP Java SDK | |
| Предложенная и согласованная protocol version | |
| Inspector pinned version / Node | |
| Transport command / args / working directory | |
| Проверка SDK subprocess / Inspector CLI / Web вручную | |

Отдельный task-server не вызывает LLM и не слушает HTTP. Старое приложение RepoPilot и MCP JAR — разные процессы/проекты. Не смешивайте SDK 2.1.0/protocol 2025-11-25 с описанием более новой ревизии2026-07-28 без отдельной миграции.

## 2. Lifecycle и capabilities

- initialize request/result: ____________________
- Какие capabilities объявлены сервером: ____________________
- initialized notification и последующие операции: ____________________
- Завершение клиента/process/EOF: ____________________
- Есть ли посторонний stdout: ____________________
- Что попало в stderr: ____________________

В stdout допустимы только UTF-8 JSON-RPC строки. stderr может содержать диагностическое сообщение без означания, что сам protocol-вызов завершился ошибкой.

## 3. Discovery

| MCP method | Найденные определения/URI | Проверенные schema/arguments | Наблюдение |
| --- | --- | --- | --- |
| tools/list | | | |
| resources/list | | | |
| prompts/list | | | |

tools/list перечисляет определения tools. Доменный listTasks вызывается через tools/call и возвращает задачи; это разные операции.

## 4. Tools

| Tool / arguments | Result content / structured data | isError или JSON-RPC error | Code / подтверждённый outcome |
| --- | --- | --- | --- |
| listTasks с default limit | | | |
| listTasks с допустимым limit | | | |
| getTask существующего ID | | | |
| Неверные типы / лишние поля | | | |
| Неизвестный корректно оформленный taskId | | | |
| Неизвестный tool / method | | | |

Не переносите HTTP400/404 из предыдущего урока на STDIO. Зафиксируйте фактическую форму error известного tool и protocol error отдельно. Публичный TaskView не должен незаметно расшириться внутренним acceptanceCriteria.

## 5. Resource и prompt

- URI project overview и MIME type: ____________________
- Фактическое содержимое публичного resource: ____________________
- analyze_task с валидным taskId: ____________________
- Число/роль сообщений, возвращённых prompts/get: ____________________
- Ошибка неизвестного resource/prompt/аргумента: ____________________

prompts/get возвращает шаблон сообщений; он не генерирует финальный ответ и сам не запускает tools/call. Resource сам по себе также не доказывает, что host включил данные в контекст модели.

## 6. Связь с4.1

- Что переиспользуется из domain catalog: ____________________
- Чем Java ToolCallback отличается от MCP transport/registration: ____________________
- Какой клиент реально вызвал MCP: ____________________
- Подключён ли MCP к /api/tools/model в этом срезе: ____________________
- Что потребуется для будущего host/HTTP-перехода4.3: ____________________

Запуск JAR не переключает локальные callbacks RepoPilot автоматически. Inspector/SDK здесь проверяют protocol без модели; обучение весов не выполняется.

## 7. Самостоятельное ДЗ: acceptance checklist policy

Базовые SDK tools/resource/prompt уже готовы. Новое поведение — ограниченный нормализованный checklist, подключённый отдельным getAcceptanceChecklist через отдельный ChecklistServerMain.

| taskId / limits | Исходные criteria metadata | Eligible / выбранные AC IDs | Used code points / omitted | Complete / budgetBlocked |
| --- | --- | --- | --- | --- |
| | | | | |
| | | | | |

- NFKC/strip/collapse whitespace: ____________________
- Blank/dedup и сохранение первого original index: ____________________
- Stable AC IDs после пропусков: ____________________
- Не поместившийся первый eligible item, без пропуска ради позднего: ____________________
- Полные элементы без обрезки emoji: ____________________
- Baseline TaskServerMain с двумя tools и ChecklistServerMain с тремя: ____________________

Точные поля и команды задания берите из README homework. Не повторяйте SDK-регистрацию лекции вместо реализации единственной policy.

## 8. Границы результата

- Что подтверждено unit/subprocess/Inspector: ____________________
- Проверялся ли Web Inspector вручную: ____________________
- Есть ли реальные model calls (ожидаемый baseline:нет): ____________________
- Непроверенные платформы/host-интеграции: ____________________
