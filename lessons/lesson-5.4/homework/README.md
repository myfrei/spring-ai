# Домашнее задание 5.4. Планировщик набора Skills

[Материалы урока](../README.md) · [Лекция](../lecture/README.md) · [Приёмочные тесты](src/test/java/dev/myfrei/repopilot/selection/SkillSelectionPolicyAcceptanceTest.java) · [Отчёт по ДЗ](SUBMISSION.md)

В [лекции](../lecture/README.md) агент видит каталог навыков, активирует нужную процедуру и отдельно читает её references. В этом задании вы добавите **предварительный выбор нескольких Skills с закреплёнными версиями и общим бюджетом**.

Пользователь может запросить несколько процедур в определённом порядке. Каталог со временем меняется, одно имя может повториться, а инструкции занимают место. Перед запуском полезно показать: какие навыки подходят по версии, какие повторяются и какие не помещаются.

Новый endpoint только строит отчёт. Он не активирует Skill, не запускает агента и не расширяет разрешения. Он использует загрузчик лекции, но добавляет собственные правила выбора.

## Что предоставлено

В `homework` полностью сохранён проект лекции: рабочие Skills, references, controlled agent, предложения patch и ручное одобрение.

В новом пакете `selection` уже готовы:

- закрытый JSON-декодер `SelectionRequest`;
- `SelectionChecker`, получающий свежий snapshot реального серверного каталога;
- неизменяемые DTO, controller, конфигурация и обработчик ошибок;
- тесты входных данных, адаптера и HTTP-контракта;
- восемь приёмочных тестов нового поведения.

**Единственная точка реализации:**

```text
src/main/java/dev/myfrei/repopilot/selection/SkillSelectionPolicy.java
```

Завершите `check(SelectionInput input)`. Не переписывайте YAML parser, загрузчик файлов, проверку ссылок, агентский цикл или HTTP adapter. Не заменяйте TODO фиктивным успешным отчётом.

## Запрос и серверные факты

```http
POST /api/skills/selection/check
Content-Type: application/json
```

```json
{
  "skills": [
    {
      "name": "java-code-review",
      "version": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
  ],
  "budgetBytes": 4096
}
```

Версия выше иллюстрирует форму hash. Возьмите реальную версию из `GET /api/skills/catalog` своего приложения.

Принимаются от 1 до 12 элементов. Каждый содержит только `name` и `version`; версия — 64 строчные hex-цифры SHA-256. `budgetBytes` — целое число от 1 до 4096. Неизвестные поля, дробное число, строка вместо числа, неверный hash или лишний каталог от клиента дают HTTP 400.

На сервере собирается:

```java
record SelectionInput(
    List<Pin> requested,
    List<Candidate> catalog,
    int budgetBytes
) {
    record Pin(String name, String version) {}
    record Candidate(String name, String version, int activationBytes) {}
}
```

`catalog` имеет не более четырёх уникальных имён. Его версия и размер определяются сервером. `activationBytes` — точное количество UTF-8 байтов успешного tool-envelope `activateSkill` для этого snapshot: JSON с `ok`, `code` и данными активации. Учтены экранирование, имя, версия и инструкции. Адаптер использует готовый `SkillSnapshot.activationBytes(name)`.

Это бюджет **первых результатов активации**. Он не считает токены, references, предыдущую историю, схемы tools и итоговый ответ. Его выполнение не гарантирует, что весь будущий prompt поместится в окно модели: штатный `ToolPromptBudget` продолжает проверять это во время run.

## Результат

```java
record SelectionReport(
    List<Check> checks,
    List<SelectionInput.Pin> selected,
    int usedBytes,
    int remainingBytes,
    boolean allSelected
) {
    record Check(String name, String version, String status) {}
}
```

`checks` содержит ровно один элемент на каждое запрошенное вхождение в исходном порядке. В нём сохраняются **запрошенные** имя и версия. `selected` содержит только выбранные pins, также в исходном порядке. Оба списка неизменяемы.

`usedBytes` — сумма серверных `activationBytes` только для выбранных элементов. `remainingBytes = budgetBytes - usedBytes`; отрицательного остатка быть не должно. `allSelected` равен `true` только тогда, когда все элементы получили статус `SELECTED`.

## Правила выбора

Обходите запрос в указанном пользователем порядке. Для текущего вхождения применяйте первый подходящий статус:

| Приоритет | Условие | Статус |
| --- | --- | --- |
| 1 | Это имя уже встречалось раньше в запросе | `DUPLICATE` |
| 2 | В серверном каталоге нет такого имени | `UNKNOWN` |
| 3 | Запрошенная версия отличается от серверной | `STALE` |
| 4 | Уже выбраны три навыка | `SELECTION_LIMIT` |
| 5 | Размер активации больше оставшегося бюджета | `OVER_BUDGET` |
| 6 | Все предыдущие проверки пройдены | `SELECTED` |

Имя считается встреченным после **любого** первого вхождения, даже если оно оказалось неизвестным, устаревшим или слишком большим. Например, устаревший `review@old` и следующий `review@current` дают `STALE`, затем `DUPLICATE`. Policy не исправляет запрос молча: пользователь должен удалить или исправить первое вхождение.

Бюджет и лимит количества расходует только `SELECTED`. Ошибка одного элемента не останавливает обход. Если большой Skill не помещается, более маленький следующий Skill ещё может быть выбран.

Пример с бюджетом 400 байтов:

| Запрос | Серверный размер | Статус | Остаток |
| --- | --- | --- | --- |
| `large` | 5000 | `OVER_BUDGET` | 400 |
| `small` | 300 | `SELECTED` | 100 |
| `tiny` | 100 | `SELECTED` | 0 |

Это пример алгоритмического контракта с искусственными размерами. Реальные инструкции из проекта занимают другое количество байтов; адаптер получает его из snapshot.

Приоритет остаётся обязательным после выбора трёх Skills: неизвестный четвёртый получает `UNKNOWN`, устаревший известный — `STALE`, повтор — `DUPLICATE`. Только новый известный Skill актуальной версии получает `SELECTION_LIMIT`.

## Последовательность работы

1. Прочитайте DTO и `SkillSelectionPolicyAcceptanceTest`.
2. Запустите базовые тесты; они зелёные ещё до реализации.
3. Включите профиль `homework`: восемь приёмочных тестов должны показать TODO.
4. Реализуйте только чистую policy.
5. Проверьте порядок, версии, точную границу бюджета и поздний небольшой Skill после большого.
6. Заполните [SUBMISSION.md](SUBMISSION.md).

```bash
./mvnw test
./mvnw -Phomework -Dtest=SkillSelectionPolicyAcceptanceTest test
./mvnw -Phomework verify
```

Без `homework` исключён только `SkillSelectionPolicyAcceptanceTest`. Остальные тесты, включая предоставленные проверки endpoint, выполняются. Приёмочные тесты выявляют `UnsupportedOperationException`, пока policy не реализована; после реализации выполните весь набор.

Проверка с PostgreSQL требует Docker:

```bash
./mvnw -Phomework,pgvector-it verify
```

Для unit-тестов не требуется загружать локальную LLM. Ответы модели в интеграционных проверках задаются HTTP-фикстурами.

## Ручная проверка

Запустите приложение по лекции. Нужны флаги `repopilot.agent.enabled`, `repopilot.workspace.enabled`, `repopilot.controlled.enabled` и `repopilot.skills.enabled`. Каталог остаётся внутри настроенного учебного workspace.

Сначала получите реальные имена и версии:

```bash
curl -sS http://localhost:8080/api/skills/catalog
```

Затем отправьте выбранные pins:

```bash
curl -sS http://localhost:8080/api/skills/selection/check \
  -H 'Content-Type: application/json' \
  -d '{"skills":[{"name":"java-code-review","version":"REPLACE_WITH_REAL_SHA256"}],"budgetBytes":4096}'
```

До реализации TODO endpoint отвечает HTTP 501 с кодом `SELECTION_NOT_IMPLEMENTED`. После реализации корректный preview отвечает HTTP 200; `allSelected=false` — обычный отчёт, а не HTTP-ошибка.

Проверьте четыре сценария:

1. Реальное имя и актуальная версия с достаточным бюджетом → `SELECTED`.
2. То же имя второй раз → `DUPLICATE` без повторного расхода байтов.
3. После получения версии вручную измените учебный `SKILL.md`, запросите выбор со старым pin → `STALE`.
4. Установите бюджет 1 → `OVER_BUDGET`, без активаций и записи файлов.

После preview можно вручную выбрать подходящий Skill для обычного запуска лекции. Сервер снова формирует snapshot и проверяет версию при активации; preview не является обещанием неизменности каталога на будущее.

## Критерии приёмки

- Все восемь приёмочных тестов и базовые тесты проходят.
- Точный приоритет, порядок и неизменяемость соответствуют контракту.
- Устаревшая версия не заменяется текущей автоматически.
- Неизвестные или пропущенные элементы не расходуют бюджет.
- Не выбирается больше трёх Skills; после большого может пройти маленький.
- Размеры и каталог невозможно подделать через JSON.
- Policy не читает файлы, не активирует Skills и не меняет разрешения.
- В отчёте указано, что байтовый бюджет не равен токенному окну модели.

Необязательное усложнение: UI предпросмотра с порядком выбора, остатком бюджета и причинами исключения. Он должен показывать решение сервера, а не реализовывать второй алгоритм в браузере.

## Читать

- [Agent Skills Specification](https://agentskills.io/specification).
- [Добавление Skills в агент и progressive disclosure](https://agentskills.io/client-implementation/adding-skills-support).
- [Java 25: StandardCharsets.UTF_8](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/charset/StandardCharsets.html).
