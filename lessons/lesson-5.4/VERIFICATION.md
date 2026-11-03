# Проверка урока 5.4

Дата: 2026-11-02. Java Oracle 25.0.5.0.3+1-LTS-2, Maven Wrapper 3.9.11, Spring Boot 4.1.2, Spring AI 2.0.2, Docker 29.4.0, Node 24.16.0. Использованы настоящие файлы, Boot HTTP и Ollama HTTP fixture; реальные веса не запускались.

## Матрица

| Срез | Java | pgvector IT | JavaScript | Stock Room |
| --- | --- | --- | --- | --- |
| lecture | 453 PASS | 39 PASS | 104 PASS | 4 PASS |
| homework, готовая база | 456 PASS | 39 PASS | 104 PASS | 4 PASS |
| Временный эталон homework | 464 PASS | 39 PASS | База без изменений | База без изменений |
| Новые приёмочные в каркасе | 8 TODO errors | Не применимо | Не применимо | Не применимо |

Все clean verify завершились без failures/errors/skips. База 5.3 сохранена целиком; добавлен 21 тест Skills. В homework три предоставленных adapter-теста зелёные до заполнения TODO, восемь новых приёмочных проверяют SkillSelectionPolicy. Временный эталон отличается от каркаса только этой policy и не входит в материалы.

## Повторение

Из lecture или homework:

```bash
./mvnw clean verify -Ppgvector-it
npm ci
npm test
./mvnw -f workspace-fixture/pom.xml test
```

Нужен Docker для прежнего RAG. Новые Skills-тесты не скачивают модель. Унаследованные MCP-профиль и серверы сохранены, но отдельный mcp-host-it в этом срезе повторно не запускался.

В homework:

```bash
./mvnw -Phomework -Dtest=SkillSelectionPolicyAcceptanceTest test
```

До решения ожидаются восемь UnsupportedOperationException из одного TODO. После решения:

```bash
./mvnw clean verify -Phomework,pgvector-it
```

## Что подтверждено

- SafeConstructor, закрытые name/description, лимиты каталога/ресурсов, duplicate YAML keys, неподдерживаемые поля, scripts, invalid UTF-8, symlink и traversal.
- В начальном model prompt только каталог; тексты неактивных Skills и references отсутствуют. Явная и model-driven активации дают разные source.
- Version включает SKILL.md и references. Изменённый reference даёт STALE_SKILL; неактивный Skill не выдаёт reference. Журнал сохраняет фактические версии и hashes.
- Бюджет проверяет весь prompt и schemas. Explicit activation трёх навыков при малом окне даёт CONTEXT_LIMIT без model/identity HTTP calls.
- Настоящий Boot/Ollama HTTP путь проходит активацию, reference, чтение Java и предложение правки; применение требует отдельного ручного решения. В ASK даже текст о разрешении внутри Skill не позволяет запись.
- Независимо запущен executable JAR: пять model calls прошли activateSkill → readSkillReference → readFile → proposePatch → ответ. В режиме ASK proposals пусты, исходные байты неизменны; server journal содержит MODEL, manifest version и hash прочитанного checklist.
- В homework сохранены все 369 файлов лекционной базы. Приёмочные отдельно проверяют порядок, повтор имени, неизвестную/устаревшую версию, лимит трёх навыков, точный byte budget и возможность выбрать маленький Skill после слишком большого.

## Граница результата

Progressive disclosure ограничивает данные в model prompt; для manifest Java заранее читает bounded набор файлов. Byte budget ДЗ относится к activation envelopes, а не к токенам всего разговора. Предварительный выбор не активирует Skills и не выдаёт разрешений. Loader поддерживает учебное подмножество формата, scripts не исполняются. Проверки не доказывают улучшение качества модели; для этого предоставлен пустой бланк сравнения на реальных весах.
