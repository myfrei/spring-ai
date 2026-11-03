# Сдача ДЗ4.4

- Автор:
- Java25 / ОС:
- Коммит или patch:

## Проверки

- Базовые unit + adapter:
- Восемь acceptance с -Phomework:
- HTTP / STDIO MCP:
- Составной mcp-host-it:
- Регрессия RAG / UI:

## Наблюдения

- Доступный MCP:
- Три транспортных сбоя:
- Следующий fail-fast: code / remoteCalls / embedding attempts / model attempts:
- Одна HALF_OPEN probe:
- Восстановление:
- Business error без открытия circuit:

## Объяснение

1. От какого момента считается cooldown при неудачной probe?
2. Как защищены повторные и поздние complete?
3. Как обеспечена одна конкурентная probe?
4. Почему AUTHORIZATION и REMOTE_TOOL не считаются сетевой аварией?
5. Чем доступность MCP отличается от готовности RAG и качества ответа модели?

## Границы решения

- Что изменено:
- Остались ли TODO:
- Известные ограничения:
