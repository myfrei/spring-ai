# Бланк эксперимента: собственный agent runtime

Заполняйте фактическими наблюдениями. Не считайте ожидаемый ответ выполненным измерением. Не сохраняйте в бланке секреты, полный prompt или private tool payload.

## Среда

| Поле | Значение |
| --- | --- |
| Дата / Java / Boot / Spring AI | |
| Chat model / digest | |
| Runtime settings / feature flags | |
| Conversation ID | |
| Run ID | |
| Вопрос / выбранный taskId | |
| Лимиты model/tools/context | |

## Один шаг цикла

| Шаг | Model attempt | Call ID в raw journal / callIdHash в events / name | Результат / ok | Событие / sequence | Причина продолжения или остановки |
| --- | --- | --- | --- | --- | --- |
| | | | | | |
| | | | | | |
| | | | | | |

Отдельно укажите: где Java проверила tool name/schema/аргументы, где исполнила доменную операцию и где добавила correlated ToolResponseMessage. Schema-only callback не должен исполнять бизнес-логику сам.

Наблюдение: 

## Agent / Conversation / Run

| Проверка | Результат |
| --- | --- |
| Одна agent definition, два разных run | |
| Какие завершённые turns доступны следующему run | |
| Что не попадает в постоянную историю | |
| Корреляция событий с run | |
| Конкурирующие запросы в одну conversation | |
| Изоляция разных conversations | |

## Стоимость и ограничения

| Показатель | Run1 | Run2 |
| --- | --- | --- |
| Model attempts / provider usage | | |
| Tool attempts / successful results | | |
| Input estimate / budget перед следующим model call | | |
| Total elapsed | | |
| Outcome / stop reason | | |
| Последняя подтверждённая стадия | | |

Не заменяйте неизвестный provider usage нулём. Лимит итераций и timeout одного HTTP-вызова не являются общим deadline run.

## Отрицательные сценарии

| Случай | Что было отправлено | Где отказ / code | Model/tool attempts после отказа |
| --- | --- | --- | --- |
| Неизвестный tool | | | |
| Неверный / повторённый call ID | | | |
| Неверные аргументы | | | |
| Оборванный ответ модели | | | |
| Исчерпан model/tool limit | | | |
| Недостаточный context budget | | | |
| Изменился model digest | | | |

## ДЗ: repeated observations

Идентичность observation определяют name/argumentsHash/resultHash/ok; текст ответа модели не заменяет эти поля.

| Суффикс наблюдений | Period / два полных повторения | Stop rule result | Следующий model call выполнен? |
| --- | --- | --- | --- |
| | | | |
| | | | |
| | | | |

Различайте повтор одинаковой полезной операции и требуемую учебную policy остановки. NO_PROGRESS означает правило runtime, а не доказательство отсутствия любого возможного дальнейшего прогресса.

## Выводы

Что подтвердил контрактный fixture: 

Что показали реальные веса: 

Что ещё не проверялось: 
