# Отчёт по ДЗ 5.9

- Commit/diff:
- Как выбирается свежий snapshot и разрешается tie:
- Как устраняются общие pending approvals:
- Почему ack не является permission:

| Проверка | Команда/шаги | Фактический результат |
| --- | --- | --- |
| Базовые тесты | `./mvnw test` | |
| 8 приёмочных | `./mvnw -Phomework -Dtest=AttentionQueuePolicyAcceptanceTest test` | |
| Полная проверка | `./mvnw -Phomework verify` | |
| UI | `npm test` | |
| Pending после ack | | |
| Новое событие после ack | | |
| Скрытые pending при малом лимите | | |
| Snapshot/approvals до и после inbox | | |

- Что произойдёт при отказе localStorage:
- Почему sequence разных сессий не сравниваются:
- Какие сценарии проверены фикстурами, какие реальной моделью:
- Известное ограничение:
