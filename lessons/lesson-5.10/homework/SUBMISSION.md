# Отчёт по ДЗ 5.10

- Commit/diff:
- Как задаётся allowlist:
- Как исключаются optional поля:
- Что именно хешируется:
- Как проверяется полный UTF-8 размер:

| Проверка | Команда/шаги | Фактический результат |
| --- | --- | --- |
| Базовые тесты | `./mvnw test` | |
| 8 приёмочных | `./mvnw -Phomework -Dtest=PortableAuditPolicyAcceptanceTest test` | |
| Полная проверка | `./mvnw -Phomework verify` | |
| Повтор одинаковых фактов | | |
| Unicode и точная граница бюджета | | |
| Порядок optional omissions | | |
| Missing required / слишком малый бюджет | | |
| Отсутствие source/diff/logs/URL/absolute path | | |
| Состояние и execution counters до/после export | | |

- Почему наличие файла не означает DONE:
- Почему checksum не подтверждает источник:
- Что нужно отдельно для воспроизведения:
- Какие проверки выполнены реальным runtime, какие фикстурами:
- Известное ограничение:
