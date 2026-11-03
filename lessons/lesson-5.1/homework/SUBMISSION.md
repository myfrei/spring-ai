# Сдача ДЗ5.1

- Автор:
- Java25 / ОС:
- Коммит или patch:

## Проверки

- Готовая база ./mvnw test:
- Восемь acceptance с -Phomework:
- RAG/UI regression:

## Новый сценарий

- conversationId / runId:
- Обычный run: status / reason / modelCalls / executedTools:
- Guarded run: status / reason / modelCalls / executedTools:
- Два одинаковых завершающих блока наблюдений (metadata):
- Связанные tool results в /messages:
- Был ли новый model call после NO_PROGRESS:

## Объяснение

1. Почему A A B C не останавливает новый progress в конце?
2. Почему одинаковые имена tools не равны одинаковым observations?
3. Почему результат STOPPED нельзя объявить выполненной задачей?
4. Где эвристика может остановить полезное повторное чтение?
5. Какие пределы runtime действуют независимо от policy?

## Границы

- Что изменено:
- Остались ли TODO:
- Реальные веса проверялись или использовалась scripted/HTTP fixture:
