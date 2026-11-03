# Бланк эксперимента: MCP Streamable HTTP

Не заполняйте таблицы предполагаемыми результатами. Зафиксируйте фактический ответ и отделите наблюдение от объяснения. Положительные операции выполняйте через закреплённый Inspector/SDK; отрицательные HTTP-проверки — отдельно.

## Среда

| Поле | Значение |
| --- | --- |
| Дата / ОС | |
| Java / Boot / Spring AI | |
| Effective MCP SDK version | |
| Inspector version / Node version | |
| Endpoint | |
| Bind address | |
| Согласованная protocol revision | |
| Способ запуска сервера | |
| Значение bearer token | Не записывать |

## Discovery и MCP-примитивы

| Операция | Что проверяли | Фактический результат | Примечание |
| --- | --- | --- | --- |
| initialize | protocol и capabilities | | |
| tools/list | Имена и input schemas | | |
| listTasks(limit=2) | Количество / публичные поля | | |
| getTask(TASK-101) | Совпадение ID / публичные поля | | |
| resources/list | URI / mime type | | |
| resources/read | Project overview | | |
| prompts/list | Имя / required argument | | |
| prompts/get | Возвращённое сообщение, без inference | | |

Проверьте отдельно: не перепутаны ли MCP tools/list и доменный listTasks; не попали ли acceptance criteria в базовый публичный TaskView. Capabilities из handshake запишите такими, как их вернул реальный SDK.

## Граница доступа

Не помещайте token в скриншоты/отчёт. Можно указать только «отсутствует», «верный» или «неверный».

| Bearer | Origin | Операция | HTTP status / MCP result | Вывод |
| --- | --- | --- | --- | --- |
| Отсутствует | Отсутствует | | | |
| Неверный | Отсутствует | | | |
| Верный | Отсутствует | | | |
| Верный | Разрешённый | | | |
| Верный | Запрещённый | | | |

Почему Origin не заменяет bearer? Почему loopback-port не является авторизацией?

Ответ: 

## Ошибки контракта

| Случай | Как отправлено | HTTP / JSON-RPC / isError / code | Что именно отвергнуто |
| --- | --- | --- | --- |
| limit=2.5 | | | |
| Неизвестное поле | | | |
| Неверный taskId | | | |
| Отсутствующая задача | | | |
| Неизвестный tool | | | |

CLI может преобразовывать аргументы по schema; raw JSON type проверяется SDK/HTTP contract tests. Успешный exit code Inspector не доказывает `isError=false`.

## Lifecycle и transport

| Наблюдение | Результат |
| --- | --- |
| Клиент disconnect, сервер остаётся жив | |
| Повторное подключение | |
| Что было JSON, что SSE на wire | |
| Отличие от chat SSE | |
| Что происходит при выключенном сервере | |

## Изменение продукта в ДЗ

Для getReleaseReadiness запишите только результаты своей реализации policy. Это оценка COURSE_FIXTURE metadata; она не доказывает запуск реального CI.

| Сценарий | Check states / revision | Verdict / reasons | Доказательство |
| --- | --- | --- | --- |
| DONE и три актуальных PASS | | | |
| Актуальный FAIL | | | |
| Пропущенный / UNKNOWN check | | | |
| Конфликт latest attempt | | | |
| Только устаревшие результаты | | | |

## Выводы

Наблюдаемое поведение: 

Ограничения эксперимента: 

Следующее изменение, которое можно проверить отдельно: 
