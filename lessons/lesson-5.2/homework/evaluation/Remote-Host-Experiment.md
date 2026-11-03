# Бланк эксперимента: MCP host → RAG → final answer

Это пустой рабочий бланк. Не переносите в него ожидаемые значения как выполненные измерения. Сравнивайте один taskId/question при одинаковых весах, корпусе и active index. Реальные веса и семантику ответа оценивайте отдельно от fixture-тестов.

## Зафиксированная среда

| Поле | Значение |
| --- | --- |
| Дата / Java / Boot / Spring AI | |
| Effective MCP SDK / protocol revision | |
| Host endpoint / MCP endpoint | |
| Chat model / digest | |
| Embedding model / digest / dimensions | |
| Active index fingerprint | |
| Corpus / chunking profile | |
| taskId / question | |
| Bearer token | Не записывать |

## Discovery и допуск

| Проверка | Наблюдение |
| --- | --- |
| Серверные tools из discovery | |
| Callback, допущенный host | |
| Квалифицированное имя | |
| Scope taskId в schema и runtime | |
| Неимпортированные tools/resources/prompts | |
| Autoimport отключён | |
| Запуск host при выключенном MCP server | |

## Сравнение режимов

| Показатель | WORKFLOW | MODEL |
| --- | --- | --- |
| Удалённые попытки getTask | | |
| Успешные verified task results | | |
| Selection model calls / usage | | |
| Retrieval searches / latency | | |
| Final model calls / usage | | |
| Общая latency | | |
| Outcome | | |
| Sources после budget | | |

Не складывайте provider usage разных этапов по повторяющимся агрегатам. Отсутствующие provider tokens оставляйте неизвестными, а не нулевыми. Transport timeout не равен общему deadline пользовательского запроса.

## Ручная проверка ответа

| Критерий | WORKFLOW | MODEL | Подтверждение |
| --- | --- | --- | --- |
| taskId совпал | | | |
| Статус/summary соответствует server result | | | |
| Документальные утверждения поддержаны источниками | | | |
| Не смешаны задача и старые сведения корпуса | | | |
| Недостаток данных обозначен честно | | | |
| Инструкции в tool result не исполнены | | | |

Протокольный успех и факт getTask не доказывают правильность всех фраз ответа.

## Отрицательные сценарии

| Сценарий | Этап остановки | HTTP / code / counters | Чего не выполняли после ошибки |
| --- | --- | --- | --- |
| MCP server выключен | | | |
| Неверный token | | | |
| Неизвестная задача | | | |
| Неверный / чужой taskId от callback | | | |
| Модель не вызвала требуемый tool | | | |
| Индекс не опубликован | | | |
| Final model недоступна | | | |

## ДЗ: circuit breaker remote gateway

Gate применяется только к remote-call. События времени задавайте Clock в тестах; не ждите реальные 30 секунд для проверки policy.

| Шаг | Время / событие | State до/после | Remote calls / permit | Результат |
| --- | --- | --- | --- | --- |
| До порога transport failures | | | | |
| Порог достигнут | | | | |
| В OPEN до cooldown | | | | |
| Первый запрос после cooldown | | | | |
| Конкурирующий запрос при probe | | | | |
| Успешный probe | | | | |
| Business isError | | | | |
| Поздний completion старого generation | | | | |

## Выводы

Что подтверждено измерениями: 

Что проверено только контрактными fixtures: 

Чего этот эксперимент не проверяет: 

Одна следующая гипотеза: 
