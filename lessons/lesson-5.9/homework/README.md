# Домашнее задание 5.9. Очередь внимания по нескольким сессиям

[Материалы урока](../README.md) · [Лекция](../lecture/README.md) · [Приёмочные тесты](src/test/java/dev/myfrei/repopilot/attention/AttentionQueuePolicyAcceptanceTest.java) · [Отчёт по ДЗ](SUBMISSION.md)

В [лекции](../lecture/README.md) Web и консоль наблюдают одну сессию через общий координатор, события и явные решения человека. В этом задании вы добавляете **отдельный обзор нескольких сессий одного диалога**.

Пользователю нужно заметить ожидающий approval, ошибку и остановленный запуск, не открывая каждую ленту. При этом отметка «Просмотрено» не должна скрывать ещё не разрешённую операцию.

Новый inbox не повторяет replay событий и не выполняет команды. Он строится по свежим серверным snapshot и показывает, что требует внимания.

## Что предоставлено

`homework` содержит всю базу лекции. Новые controller, DTO, decoder, адаптер, страница `attention.html` и её JS-модуль уже готовы. Есть восемь приёмочных тестов, три теста Java-адаптеров и три JS-теста страницы.

Единственный TODO:

```text
src/main/java/dev/myfrei/repopilot/attention/AttentionQueuePolicy.java
```

Реализуйте `check(AttentionQueueInput input)`. Не переписывайте координатор, long polling, REPL, ручное решение или UI. Policy не получает approval summary, ответ модели, план или сырые события.

## Запрос

```http
POST /api/sessions/conversations/{id}/attention/check
Content-Type: application/json
```

```json
{
  "maxItems": 12,
  "acknowledgedThrough": {
    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa": 5
  }
}
```

`maxItems` — целое число 1–12. Необязательная map подтверждений содержит UUID сессии и неотрицательную целую позицию её событий. Используйте реальные ID. Максимум 64 записи; неизвестная сессия, позиция из будущего, дроби или лишние поля дают HTTP 400. Неизвестный диалог — HTTP 404.

Это подтверждение **просмотра интерфейса**, а не permission на действие. Сервер не меняет approvals, jobs, планы или event cursor. Локальные отметки нескольких вкладок не являются общим серверным журналом подтверждений.

Адаптер загружает `SessionCoordinator.sessions(conversationId)` и передаёт:

```java
record AttentionQueueInput(
    UUID conversationId, List<Session> sessions, int maxItems
) {
    record Session(
        UUID id, String status, String stopReason,
        long lastSequence, long acknowledgedThrough,
        List<Approval> approvals
    ) {}
    record Approval(String kind, UUID id, String status) {}
}
```

Отсутствующее подтверждение представлено `-1`. Серверные snapshots ограничены 64 сессиями и 256 approval refs на сессию. Списки неизменяемы. UI не может прислать собственные статусы или разрешения.

## 1. Выберите свежий snapshot каждой сессии

Если ID сессии повторился, используйте snapshot с **наибольшим `lastSequence`**. При равенстве возьмите последний элемент исходного списка. Место сессии в общем порядке остаётся местом её первого появления.

Нельзя просто взять последний элемент независимо от sequence: позже в списке может оказаться более старый snapshot.

Позиции разных сессий не сравниваются. `sequence=900` одной сессии не делает её важнее другой с `sequence=3`.

## 2. Добавьте ожидающие разрешения

Из выбранных snapshots учитывайте только refs со статусом `PENDING`. `PREVIEW` и завершённые состояния не требуют решения.

Ключ глобального устранения повторов:

```text
approval:<kind>:<id>
```

`PATCH` и `VERIFICATION` с одинаковым UUID — разные записи. Один verification request может быть виден в нескольких сессиях; показывайте его один раз, от первой подходящей сессии. Не считайте `PREVIEW` первым подходящим вхождением: он не должен скрывать последующий `PENDING`.

Уведомление:

- `kind = APPROVAL_REQUIRED`;
- `blocking = true`;
- `approvalKind` и `approvalId` сохранены;
- `code = PENDING`;
- `sessionId` и `sequence` взяты у выбранного представителя.

**Подтверждение просмотра не скрывает pending approval**, даже если его sequence уже просмотрен. Исчезнуть он может после изменения реального серверного состояния.

## 3. Добавьте непросмотренные завершения

Состояния `FAILED` и `STOPPED` дают отдельное неблокирующее уведомление, если:

```text
acknowledgedThrough < lastSequence
```

Его ключ — `session:<id>:terminal`, `approvalKind` и `approvalId` равны `null`, `code` содержит серверный `stopReason`. Для `QUEUED`, `RUNNING`, `COMPLETED` отдельного terminal notice нет.

Одна сессия может дать и pending approval, и ошибку. Это две разные причины внимания. Новая серверная позиция после подтверждения может показать terminal notice снова: подтверждение относится к увиденной версии snapshot.

## 4. Отсортируйте и ограничьте очередь

Приоритеты:

1. `APPROVAL_REQUIRED`.
2. `FAILED`.
3. `STOPPED`.

При равенстве сохраняйте порядок выбранных сессий и refs. Не используйте sequence разных сессий как общий clock.

Возвращайте первые `maxItems` уведомлений. Лимит может скрыть часть pending approvals, поэтому это обязательно отражается в отчёте:

```java
record AttentionQueueReport(
    UUID conversationId, List<Notice> notices,
    int eligibleCount, int blockingCount,
    int omittedCount, int omittedBlockingCount,
    boolean allActionableVisible,
    Map<UUID, Long> highWatermarks
) {}
```

- `eligibleCount`: число уведомлений после устранения повторов и подтверждений, до лимита.
- `blockingCount`: число всех pending approvals в этой очереди.
- `omittedCount`: сколько уведомлений осталось за лимитом.
- `omittedBlockingCount`: сколько pending approvals осталось за лимитом.
- `allActionableVisible`: `true`, только если `omittedBlockingCount == 0`.
- `highWatermarks`: последний sequence каждого выбранного snapshot, включая сессии без notices.
- Список и map результата неизменяемы.

Нельзя выдать «всё видно», если лимит скрыл обязательное решение. После просмотра видимых approvals пользователь открывает обычную сессию, принимает решение и обновляет inbox.

## Готовый UI

Откройте `/attention.html?conversationId=<реальный-UUID>`. Страница:

- получает текущие сессии и отправляет только лимит и позиции просмотра;
- хранит отметки в отдельном версионированном ключе localStorage данного диалога;
- отбрасывает устаревшие или некорректные локальные записи;
- при отказе хранилища продолжает работать в памяти;
- выводит метаданные через `textContent`;
- открывает `/agent.html?sessionId=...` без нового запуска;
- не содержит approve/reject/cancel buttons.

Кнопка «Просмотрено» есть только у неблокирующих уведомлений. Исходный интерфейс лекции сохранён. В npm test добавлен только новый тестовый файл, остальные тесты продолжают выполняться.

## Проверки

```bash
./mvnw test
./mvnw -Phomework -Dtest=AttentionQueuePolicyAcceptanceTest test
./mvnw -Phomework verify
npm ci
npm test
```

Без `homework` исключён только новый приёмочный класс. Базовые проверки и тесты Java-адаптеров выполняются; приёмочные тесты выявляют TODO policy. UI-тесты используют HTTP-фикстуры готового контракта. После реализации проверьте и выбор очереди, и отображение результата.

Для PostgreSQL требуется Docker:

```bash
./mvnw -Phomework,pgvector-it verify
./mvnw -f workspace-fixture/pom.xml test
```

## Ручная проверка

Запустите проект с девятью флагами лекции: `agent`, `workspace`, `controlled`, `skills`, `plans`, `compaction`, `verification`, `research`, `sessions` через `repopilot.<имя>.enabled`.

Создайте несколько обычных jobs одного диалога по лекции, затем откройте inbox. До TODO endpoint возвращает HTTP 501 с `ATTENTION_NOT_IMPLEMENTED`.

После реализации проверьте: pending approval остаётся после подтверждения sequence; terminal notice скрывается после «Просмотрено»; новый sequence может вернуть его; маленький лимит явно сообщает о скрытых pending approvals. Сравните snapshots и approvals до/после: inbox не меняет их.

## Приёмка

- Все восемь приёмочных тестов, базовые и JS-проверки проходят.
- Свежий snapshot выбран по sequence; tie — последний элемент.
- Pending approvals не скрываются подтверждением и не дублируются между jobs.
- Приоритеты и порядок сохранены; скрытые approvals посчитаны.
- Нет runtime-команд, новых запусков или изменений разрешений.
- В [SUBMISSION.md](SUBMISSION.md) показаны сценарии просмотра и ограничения очереди.

## Читать

- [Java 25: LinkedHashMap](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/LinkedHashMap.html).
- [Web Storage](https://html.spec.whatwg.org/multipage/webstorage.html).
