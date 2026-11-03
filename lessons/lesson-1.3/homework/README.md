# ДЗ 1.3. Бюджет вывода streaming-ответа

[Материалы урока](../README.md) · [Лекция](../lecture/README.md) · [Приёмочные тесты](src/test/java/dev/myfrei/repopilot/chat/OutputQuotaAcceptanceTest.java) · [Отчёт по ДЗ](SUBMISSION.md)

## Чем задание отличается от лекции

Обычный SSE endpoint, parser, UI, cancel и retry из лекции здесь **уже работают**. Пользовательское приращение: запрос может установить ограничение длины ответа. При превышении показывается полученный префикс, запрос прекращается и честно помечается незавершённым.

Предоставлен новый endpoint `POST /api/chat/limited-stream` и DTO. Реализуйте только [OutputQuota.apply](src/main/java/dev/myfrei/repopilot/chat/OutputQuota.java), преобразующий поток ChatStreamEvent. Старый `/api/chat/stream` остаётся без квоты.

## API

```http
POST /api/chat/limited-stream
Content-Type: application/json

{"message":"Расскажи про records","maxCodePoints":120}
```

maxCodePoints по умолчанию 1000, допустимы 1..6000. message валидируется как раньше. После начала SSE код ошибки находится в событии failed, HTTP остаётся 200.

## Контракт оператора

1. Неверная квота отклоняется IllegalArgumentException, не трактуется как «без ограничений».
2. Состояние счётчика создаётся **на каждую подписку**. Повторная подписка к тому же Flux получает полный бюджет.
3. Считайте Unicode code points текста delta. Emoji из surrogate pair считается одним, префикс нельзя обрезать посередине корректной пары.
4. started и события provider передаются в прежнем порядке.
5. Пока delta помещается, передать её целиком. При переполнении передать только помещающийся непустой префикс и один failed с code `OUTPUT_BUDGET`.
6. Если остаток нулевой, новая текстовая delta вызывает только failed, без пустой дополнительной delta.
7. После собственного failed отменить upstream; последующие completed/delta не передавать.
8. Если модель нормально завершилась ровно на границе, сохранить completed. Если provider прислал failed раньше — сохранить его code/message.

Контрольный source содержит отдельные валидные Unicode strings. Восстановление некорректных/разорванных surrogate pairs provider внутри разных JSON delta не входит в эту задачу. Не считайте code points точным tokenizer budget.

Не собирайте весь ответ в список или StringBuilder ради ограничения. Пользователь должен видеть префикс по мере поступления. Не возвращайте completed после обрезания: это скрывает неполный ответ.

## Проверки

```bash
./mvnw -DskipTests package
./mvnw -Dtest=OutputQuotaAcceptanceTest test
./mvnw test
npm ci
npm test
```

Приёмочные проверки охватывают переполнение и upstream cancel, точную границу, нулевой остаток, emoji, provider failure, независимые подписки, wiring нового контроллера/headers и неверные лимиты. Реализуйте OutputQuota, затем проверьте новую функцию вместе с обычным чатом, streaming и готовым JavaScript-интерфейсом. Не изменяйте тесты ради зелёного результата.

## Ручной пример

```bash
curl -N http://127.0.0.1:8080/api/chat/limited-stream -H 'Content-Type: application/json' -d '{"message":"Подробно объясни generics","maxCodePoints":120}'
```

При длинном ответе ожидайте started → delta* → failed/OUTPUT_BUDGET. Более короткий законченный ответ остаётся completed. Отмена HTTP-потока не доказывает мгновенную остановку физических вычислений runtime.

## Сдача

[SUBMISSION.md](SUBMISSION.md): результат тестов, фактический terminal исход и число полученных code points, отличие лимита приложения от num_predict. Усложнение — отдельный общий deadline, который не подменяет уже полученный terminal event.
