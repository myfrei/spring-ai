# Урок 3.1. Найти причину плохого ответа

[Материалы урока](../README.md) · [Домашнее задание](../homework/README.md) · [Тесты примера](src/test/java/) · [Все уроки](../../../README.md#программа)

RepoPilot уже сравнивает CHAT, FULL_CONTEXT и RAG на одном наборе вопросов. Но строка «ответ неверный» не объясняет, что менять. В этом занятии вы добавите наблюдение за отдельными стадиями и научитесь отделять факт от предполагаемой причины. Результат — диагностический trace, три воспроизводимых учебных сбоя, разбор настоящего benchmark и Markdown-отчёт с проверяемой гипотезой.

- [Самостоятельное ДЗ: очередь ручного разбора](../homework/README.md).
- [Бланк расследования двух случаев](evaluation/Failure-Investigation.md).
- [Проверки и границы результата](../VERIFICATION.md).
- [Предыдущий урок 2.4](../../lesson-2.4/README.md).

Стек примера: Java 25, Spring Boot 4.1.2, Spring AI 2.0.2, SQLite для истории, PostgreSQL/pgvector для retrieval, локальный Ollama. Новых Maven-зависимостей относительно 2.4 нет. Версии проекта закреплены в [pom.xml](pom.xml).

## 1. Что должен уметь продукт после занятия

Пользователь выбирает неудачный ответ из сравнительного эксперимента и видит:

1. Какой case, режим, повтор, версия корпуса и dataset участвовали в запросе.
2. Завершился ли запрос и на какой стадии возникла ошибка.
3. Были ли нужные свидетельства среди найденных кандидатов.
4. Попали ли эти свидетельства в окончательно собранный контекст.
5. Как ручная оценка ответа отличается от ручной оценки цитат.
6. Какие наблюдения подтверждены, какая причина пока предполагается и какой следующий эксперимент её проверит.

Мы добавляем диагностику к уже работающему pipeline. В этом занятии не выбираем новую embedding-модель и не улучшаем результат произвольным количеством изменений. Сначала делаем ошибку наблюдаемой; конкретные оптимизации Advisors, query rewrite и reranking появятся дальше.

Ориентир на два часа: 20 минут на постановку и evidence, 25 на контракт trace и хранение, 30 на встраивание в benchmark, 20 на тренажёр и классификатор, 15 на UI и проверки, 10 на собственное расследование. Запуск настоящих моделей и повторные измерения могут потребовать дополнительного времени.

## 2. Один неверный ответ — несколько разных задач

Пусть контрольный вопрос: «Какой порт использует TaskShelf?» В учебном корпусе правильный факт — `8091`. Модель отвечает `9999`. По одному ответу невозможно понять причину.

| Наблюдение | Где продолжить проверку | Что пока нельзя заключать |
| --- | --- | --- |
| Нужного текста нет в актуальном корпусе | Данные и ожидания dataset | Что similarity search сломан |
| Текст есть, но нужный фрагмент не найден | Chunking, query embedding, фильтры, topK/threshold | Что проблема в генерации |
| Фрагмент найден, но исключён из плана | Отбор, размер чанков, бюджет | Что модель видела этот факт |
| Фрагмент включён в план, ответ неверный | Инструкция, вопрос, генерация, ручная разметка | Что доказано внутреннее решение модели «игнорировать» |
| Факт верен, ссылка указывает на другой текст | Цитаты и связь утверждений с источниками | Что правильный ответ автоматически имеет правильные цитаты |
| Запрос упал или был отменён | Исполнение и условия эксперимента | Что незавершённый результат пригоден для оценки качества |

Формулировка «модель проигнорировала документ» удобна для разговора, но слишком сильна для отчёта. Мы наблюдаем собранный контекст и выход модели. Её внутреннее решение этим не измеряется. Поэтому категория называется `GENERATION_REVIEW`: контекст содержит размеченное свидетельство, а ответ требует отдельного разбора.

Рабочая последовательность всегда одна:

**Симптом → наблюдаемые факты → гипотеза → эксперимент с одним изменением → вывод с ограничениями.**

Пример корректного вывода: «На DEV-кейсе dev-01 увеличение входного бюджета при неизменных остальных условиях вернуло нужный chunk в план и исправило ответ в двух повторах». Это локальный результат, а не обещание, что больший бюджет исправляет любой RAG.

## 3. Запускаем готовый пример двумя способами

Все команды этого файла выполняются из `lessons/lesson-3.1/lecture`. Нужен JDK 25. Node используется только для frontend-тестов. Для быстрого знакомства с диагностикой настоящие веса и PostgreSQL не требуются:

```bash
java -version
./mvnw test
npm ci
npm test
./mvnw spring-boot:run -Dspring-boot.run.arguments=--repopilot.rag.enabled=false
```

Откройте [RepoPilot](http://127.0.0.1:8080/) и нажмите «Тренажёр: три сбоя». Он прогоняет заранее заданные retrieval/ответы через настоящие локальные `CorpusCatalog`, `EvidenceCoverage` и `RagPlanner`. Ожидаются три категории: `RETRIEVAL_MISS`, `CONTEXT_DROPPED`, `GENERATION_REVIEW`. Заголовок явно сообщает `SYNTHETIC_LAB`. Кнопка «Открыть trace» показывает порядок стадий и маркеры источников.

Чтобы исследовать свою модель, остановите этот процесс и запустите полный вариант:

```bash
docker compose up -d
ollama pull embeddinggemma:300m
ollama pull qwen2.5:3b
./mvnw spring-boot:run
```

Ollama должен быть запущен отдельно — приложением Ollama или `ollama serve`. Веб-приложение слушает 127.0.0.1:8080, PostgreSQL — 127.0.0.1:5433. Если веса уже загружены, повторно скачивать их не нужно. `pull-model-strategy: never` оставляет загрузку под вашим управлением.

В UI индексируйте корпус и опубликуйте индекс в pgvector, как в 2.3. Затем выполните небольшой сравнительный прогон на `dev-01,dev-02` с одним повтором. Прочитайте ответы и sources, сохраните ручные оценки. Нажмите «Разобрать последний эксперимент» и откройте trace интересующей строки.

Быстрая проверка API тренажёра:

```bash
curl -sS -X POST http://127.0.0.1:8080/api/diagnostics/lab
```

Наличие результата тренажёра подтверждает работу диагностического механизма. Оно не измеряет качество `qwen2.5:3b`, embeddinggemma или другого набора весов: в тренажёре Ollama и SQL вообще не вызываются.

## 4. Продолжаем проект 2.4

Для пошаговой сборки возьмите готовую `lecture` из 2.4 и перенесите Wrapper, POM, `src`, `package.json` и lockfile в отдельную рабочую папку. Поменяйте имя приложения и artifactId на урок 3.1. Не переносите `target`, `node_modules`, `data`, личные переписки и старый индекс. Готовая папка этого урока уже содержит итог всех шагов.

Существующие `EvaluationDataset`, `RunStore`, `EvidenceCoverage`, `ComparisonEngine` и `ExperimentCoordinator` остаются основой. Новый пакет `dev.myfrei.repopilot.diagnostics` разделяет данные наблюдения, правила и представление:

| Файлы | Задача |
| --- | --- |
| DiagnosticTrace / SourceMarks | Ограниченная модель событий и безопасные маркеры источников |
| TraceSession / TraceRecorder / TraceLog | Жизненный цикл одного запроса и корреляция логов |
| TraceStore | Сохранение и загрузка локального trace |
| DiagnosticRules / DiagnosticReport | Проверяемые признаки, категория, гипотеза и следующий эксперимент |
| DiagnosticService | Анализ существующего run и проверка совместимости |
| DiagnosticLab | Три контролируемых сценария без моделей |
| DiagnosticMarkdown | Экспорт фактов и незаполненного подтверждённого вывода |
| DiagnosticConfiguration / Controller | Spring beans и REST API |

Меняются также `Experiment.Result` — получает `traceId`, `ComparisonEngine` — сообщает стадии, `ExperimentCoordinator` — создаёт trace и завершает его. UI получает [diagnostics.js](src/main/resources/static/js/diagnostics.js).

Trace в этом примере записывается для **benchmark attempts**, включая warm-up. У обычного интерактивного чата, ingestion и произвольных HTTP-запросов такой trace автоматически не появляется. Интерфейс анализа показывает измеряемые `run.results`; warm-up остаётся отдельно в исходном run.

## 5. Сначала размечаем ожидаемое свидетельство

Откройте [evaluation/cases.json](src/main/resources/evaluation/cases.json). Для answerable-вопроса нужны не только ожидаемые слова ответа, но и проверяемые фрагменты документов. Вопрос dev-01 допускает несколько альтернативных групп свидетельств: один факт может быть и в обзоре, и в runbook.

```json
{
  "id": "dev-01",
  "split": "DEV",
  "kind": "FACT",
  "question": "Какой порт использует TaskShelf?",
  "expectedAnswer": "8091.",
  "evidenceGroups": [
    [
      {
        "documentId": "overview",
        "excerpt": "Приложение слушает порт 8091."
      }
    ],
    [
      {
        "documentId": "runbook",
        "excerpt": "с профилем local на порту 8091"
      }
    ]
  ]
}
```

Этот фрагмент взят из текущего dataset. При изменении корпуса сверьте excerpts с исходными документами. Внутри группы должны присутствовать все evidence; достаточно совпадения одной из альтернативных групп.

Уже написанный в 2.4 `EvidenceCoverage` делает эту проверку детерминированной:

```java
package dev.myfrei.repopilot.evaluation;

import java.text.Normalizer;
import java.util.List;
import dev.myfrei.repopilot.evaluation.Experiment.Source;
public final class EvidenceCoverage {
    private EvidenceCoverage() { }
    public static String normalize(String text) { return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip(); }
    public static Boolean hit(EvaluationCase entry, List<Source> sources) {
        if (!entry.answerable()) return null;
        return entry.evidenceGroups().stream().anyMatch(group -> group.stream().allMatch(evidence -> sources.stream().anyMatch(source ->
                source.documentId().equals(evidence.documentId()) && normalize(source.text()).contains(normalize(evidence.excerpt())))));
    }
}
```

Здесь нет семантического судьи: нормализуются Unicode и пробелы, затем ищется буквальный excerpt в источнике с нужным documentId. Это **coverage заданной разметки**, а не универсальный retrieval recall. Перефразированное, но достаточное свидетельство может не совпасть. Ошибочный excerpt способен создать ложный сигнал проблемы.

Мы считаем два разных признака для того же вопроса:

- `retrievedEvidenceHit` — есть ли полная evidence-группа среди найденных кандидатов.
- `providedEvidenceHit` — есть ли она среди источников, включённых в план.

У `UNKNOWN` нет размеченного подтверждаемого факта; для него coverage возвращает `null`. Значение `null` не превращаем ни в `false`, ни в `true`. Правильность отказа проверяет человек. Ручные `answer`, `citations` и `refusalCorrect` остаются отдельными полями.

Проверка шага: если вы удалите нужный chunk из списка retrieved, первый признак должен стать false. Если сохраните его в retrieved, но удалите из selected, первый остаётся true, второй становится false. Если меняете сам dataset, меняется datasetVersion, и старый run нельзя оценивать как совместимый без исходной разметки.

## 6. Создаём контракт trace без текстов

Trace — последовательность наблюдаемых стадий одного attempt. Полный contract находится в [DiagnosticTrace.java](src/main/java/dev/myfrei/repopilot/diagnostics/DiagnosticTrace.java). Начните с его records и enum:

```java
package dev.myfrei.repopilot.diagnostics;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.myfrei.repopilot.evaluation.Experiment.Mode;

public record DiagnosticTrace(UUID id, Context context, String startedAt, String finishedAt, String outcome,
        List<Event> events, List<SourceMark> sources) {
    public DiagnosticTrace {
        events = List.copyOf(events); sources = List.copyOf(sources);
        if (id == null || context == null || startedAt == null || finishedAt == null || events.size() > 64) throw new IllegalArgumentException("Invalid trace");
        dev.myfrei.repopilot.evaluation.Experiment.Outcome.valueOf(outcome);
        for (int i = 0; i < events.size(); i++) if (events.get(i).sequence() != i + 1) throw new IllegalArgumentException("Non-contiguous events");
    }
    public enum Stage { REQUEST, GUARD, RETRIEVAL, ASSEMBLY, GENERATION, FIRST_TEXT, VERIFY, FINISH }
    public enum Status { STARTED, COMPLETED, FAILED, SKIPPED, CANCELLED }
    public record Context(UUID runId, String caseId, int ordinal, int repetition, Mode mode,
            boolean synthetic, String corpusVersion, String datasetVersion) {
        public Context {
            if (runId == null || mode == null || caseId == null || !caseId.matches("[a-z0-9-]{1,40}")
                    || !hashOrEmpty(corpusVersion) || !hashOrEmpty(datasetVersion)) throw new IllegalArgumentException("Invalid trace context");
        }
    }
    public record Event(int sequence, Stage stage, Status status, double elapsedMs,
            Map<String, Integer> counts, Map<String, String> hashes, String code) {
        private static final java.util.Set<String> COUNTS = java.util.Set.of("requestChars", "retrieved", "selected", "omitted", "estimated", "inputBudget", "deltas", "answerChars", "providerInput", "providerOutput");
        private static final java.util.Set<String> HASHES = java.util.Set.of("promptHash", "indexFingerprint");
        public Event {
            counts = Map.copyOf(counts); hashes = Map.copyOf(hashes);
            if (sequence < 1 || stage == null || status == null || !Double.isFinite(elapsedMs) || elapsedMs < 0
                    || !COUNTS.containsAll(counts.keySet()) || counts.values().stream().anyMatch(value -> value < 0)
                    || !HASHES.containsAll(hashes.keySet()) || hashes.values().stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))
                    || code == null || !code.matches("[A-Z0-9_]{0,64}")) throw new IllegalArgumentException("Unsafe trace event");
        }
    }
    public record SourceMark(String documentId, String chunkId, String textHash, double score, boolean selected) {
        public SourceMark {
            if (!documentId.matches("[a-z0-9-]{1,40}") || !chunkId.matches("[0-9a-f]{64}") || !textHash.matches("[0-9a-f]{64}")
                    || !Double.isFinite(score)) throw new IllegalArgumentException("Invalid source marker");
        }
    }
    private static boolean hashOrEmpty(String value) { return value != null && (value.isEmpty() || value.matches("[0-9a-f]{64}")); }
}
```

Ключи `counts` и `hashes` ограничены белыми списками. В `code` допускается короткий технический код, а не exception message. Отрицательные счётчики, NaN/Infinity, произвольные поля и недопустимые ID отвергаются. Это делает запрет raw payload частью структуры, а не просьбой к каждому разработчику.

Полезные поля контекста: `runId`, `caseId`, `ordinal`, `repetition`, `mode`, `synthetic`, версии корпуса и dataset. UUID trace уникален для каждого attempt. Ordinal отличает строки, caseId — смысловой контрольный вопрос, repetition — повтор. У warm-up другие ordinal/repetition; это не дополнительное измерение качества.

| Стадия | Что она означает в benchmark |
| --- | --- |
| REQUEST | Начало attempt и размер вопроса в символах |
| GUARD | Проверка неизменности условий до запроса |
| RETRIEVAL | Query embedding и поиск кандидатов в RAG |
| ASSEMBLY | Отбор по бюджету и проекция окончательного prompt |
| GENERATION | Вызов модели, завершение/ошибка или явный пропуск |
| FIRST_TEXT | Первый непустой текстовый delta |
| VERIFY | Повторная проверка условий после запроса |
| FINISH | Итог attempt |

У CHAT/FULL_CONTEXT нет RAG retrieval. При пустом selected наборе модель не вызывается: фиксируется `GENERATION / SKIPPED / NO_EVIDENCE`. Это осмысленный исход, а не «генерация заняла ноль миллисекунд».

`elapsedMs` — накопительное время от начала TraceSession. Разность двух времён помогает увидеть участок, но это не готовая длительность каждой строки. Старый `CaseMeasurement` по-прежнему отвечает за сравнительные latency-метрики и TTFT. В trace нет отдельного события на каждый токен: есть первый текст и итоговый счётчик deltas.

## 7. Отделяем тексты источников от их маркеров

`RagHit` содержит исходный текст, path, title и section. Сохранять весь объект в лог было бы просто, но тогда корпус попадёт в техническую телеметрию. Создайте проекцию `SourceMarks`:

```java
package dev.myfrei.repopilot.diagnostics;

import java.util.List;
import java.util.HashSet;
import dev.myfrei.repopilot.rag.RagHit;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.SourceMark;
public final class SourceMarks {
    private SourceMarks() { }
    public static List<SourceMark> from(List<RagHit> hits, List<String> selectedIds) {
        var all = new HashSet<String>();
        for (var hit : hits) if (!all.add(hit.source().chunkId())) throw new IllegalArgumentException("Duplicate retrieved ID");
        if (new HashSet<>(selectedIds).size() != selectedIds.size() || !all.containsAll(selectedIds)) throw new IllegalArgumentException("Selection outside retrieval");
        return hits.stream().map(hit -> new SourceMark(hit.source().documentId(), hit.source().chunkId(), hit.source().textHash(),
                hit.source().score(), selectedIds.contains(hit.source().chunkId()))).toList();
    }
}
```

Проверяются два инварианта: chunkId кандидатов уникальны; selected IDs уникальны и принадлежат retrieved. В маркере остаются только documentId, chunkId, textHash, score и флаг selected. Текст, путь, заголовок и embedding-вектор не сериализуются.

Это позволяет проверить: «данный chunk найден, но не выбран». По одному хешу нельзя прочитать документ и оценить истинность ответа. Для содержательного разбора открываем исходный evaluation run с сохранёнными sources и версией корпуса. Хеш не является шифрованием; низкоэнтропийные данные по нему иногда угадываются. Локальная диагностика остаётся данными вашего проекта.

При ошибке metadata trace отмечает `TRACE_METADATA_INVALID`; это повод проверить перенос источников. Такой сигнал не должен незаметно становиться «retrieval исправен». Классификатор и человек должны различать отсутствие доказательств и отрицательный результат проверки.

## 8. Жизненный цикл TraceSession и корреляция логов

Создайте [TraceSession.java](src/main/java/dev/myfrei/repopilot/diagnostics/TraceSession.java). Сессия получает UUID, context, монотонные часы и два sink: лог события и сохранение завершённого trace. Они передаются в конструктор, поэтому часы и отказы хранилища можно проверить без моделей.

Ключевой участок записи события:

```java
    public synchronized void event(Stage stage, Status status, Map<String, Integer> counts, Map<String, String> hashes, String code) {
        if (this.id == null || this.closed || (this.events.size() >= 63 && stage != Stage.FINISH)) return;
        var event = new Event(this.events.size() + 1, stage, status, Math.max(0, this.clock.getAsLong() - this.start) / 1_000_000.0,
                counts, hashes, safeCode(code)); this.events.add(event);
        try { this.logger.accept(event); } catch (RuntimeException ignored) { /* Telemetry must not fail a request. */ }
    }
```

Всего допускается 64 события; после 63 обычные события перестают добавляться, последнее место зарезервировано для FINISH. Методы синхронизированы, так как Reactor callbacks могут выполняться на другом потоке. Сбой логирования не прерывает бизнес-запрос. Завершение идемпотентно: повторный `finish` не меняет уже записанный outcome.

Полный raw retrieved временно нужен сессии, чтобы сформировать маркеры selected. После `finish` ссылка на него очищается. В сериализованный trace raw retrieved не попадает. Сохранение на диск также не должно превращать удачный ответ в ошибку: UI покажет недоступность trace, если файл не записался.

[TraceRecorder.java](src/main/java/dev/myfrei/repopilot/diagnostics/TraceRecorder.java) создаёт session и пишет структурированную строку через TraceLog:

```java
package dev.myfrei.repopilot.diagnostics;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import dev.myfrei.repopilot.diagnostics.DiagnosticTrace.*;
public class TraceRecorder {
    private final TraceStore store;
    public TraceRecorder(TraceStore store) { this.store = store; }
    public boolean available(String id) { try { this.store.load(UUID.fromString(id)); return true; } catch (RuntimeException error) { return false; } }
    public TraceSession begin(Context context) {
        UUID id = UUID.randomUUID();
        var session = new TraceSession(id, context, System::nanoTime,
                event -> LoggerFactory.getLogger(TraceRecorder.class).info("trace_event {}", TraceLog.line(id, context, event)), this.store::save);
        session.event(Stage.REQUEST, Status.STARTED); return session;
    }
}
```

Одна строка имеет префикс `trace_event` и JSON с traceId/runId/caseId/ordinal/mode/synthetic/event. В TraceLog нет обращения к вопросу, ответу или источникам. Для локальной проверки сохраните вывод приложения в файл и ищите нужный traceId:

```bash
./mvnw spring-boot:run > repopilot.log 2>&1
rg 'trace_event' repopilot.log
# Вместо значения ниже подставьте traceId интересующей строки:
rg '00000000-0000-0000-0000-000000000000' repopilot.log
```

Не добавляйте `log.info("request={}", request)` или логирование exception payload рядом с этим кодом. Так можно обойти тщательно ограниченный контракт trace.

Здесь trace — **локальный журнал стадий приложения**, не OpenTelemetry distributed trace. UUID не является автоматически traceparent. Мы не подключаем collector, exporter, Grafana или Micrometer Tracing. У Spring AI есть собственные observations, но их включение, перенос контекста между сервисами и экспорт — отдельная интеграция. Официальное описание границ компонентов: [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/index.html).

## 9. Сохраняем trace атомарно

Создайте [TraceStore.java](src/main/java/dev/myfrei/repopilot/diagnostics/TraceStore.java). Используем прежний подход RunStore: временный файл в той же директории и atomic move.

```java
package dev.myfrei.repopilot.diagnostics;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import dev.myfrei.repopilot.evaluation.EvaluationProblem;
import tools.jackson.databind.json.JsonMapper;
public class TraceStore {
    private final Path directory;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public TraceStore(String directory) { this.directory = Path.of(directory).toAbsolutePath(); }
    public synchronized void save(DiagnosticTrace trace) {
        Path temporary = null;
        try {
            Files.createDirectories(this.directory); byte[] bytes = this.mapper.writeValueAsBytes(trace);
            if (bytes.length > 262144) throw new IOException("Trace too large");
            temporary = Files.createTempFile(this.directory, "trace-", ".tmp"); Files.write(temporary, bytes);
            Files.move(temporary, path(trace.id()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw new EvaluationProblem("TRACE_WRITE_FAILED", "Не удалось сохранить диагностический trace."); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    public synchronized DiagnosticTrace load(UUID id) {
        if (!Files.exists(path(id))) throw new EvaluationProblem("NOT_FOUND", "Trace не найден. Возможно, диагностика была выключена или файл не сохранился.");
        try {
            if (Files.size(path(id)) > 262144) throw new IOException("Trace too large");
            var trace = this.mapper.readValue(Files.readAllBytes(path(id)), DiagnosticTrace.class);
            if (!trace.id().equals(id) || trace.events().size() > 64) throw new IOException("Trace identity/size mismatch");
            return trace;
        } catch (IOException | RuntimeException error) { throw new EvaluationProblem("TRACE_CORRUPT", "Сохранённый trace не прошёл проверку."); }
    }
    private Path path(UUID id) { return this.directory.resolve("trace-" + id + ".json"); }
}
```

Имя строится из типизированного UUID, поэтому API не принимает файловый путь от клиента. При чтении проверяются размер, формат и совпадение ID с содержимым. Максимум одного файла — 262144 байта. Неизвестный trace даёт NOT_FOUND, повреждённый — TRACE_CORRUPT.

Размер ограничен для одного trace; автоматической очистки старых файлов и общей дисковой квоты пока нет. Для учебного проекта заранее выберите папку и удаляйте свои отработанные trace по собственной политике хранения. Сырые evaluation runs в `data/evaluation` — отдельные артефакты: они содержат ответы и sources. Запрет payload в trace не делает эти runs обезличенными.

В `application.yaml` добавьте параметры:

```yaml
repopilot:
  diagnostics:
    enabled: true
    trace-directory: ${REPOPILOT_TRACE_DIR:./data/diagnostics/traces}
```

При выключенной diagnostics recorder отсутствует; engine использует `TraceSession.disabled()`. Контроллер и beans включаются только вместе с `repopilot.evaluation.enabled=true`. Старые исходы benchmark остаются читаемыми без trace.

## 10. Встраиваем trace в настоящий pipeline

Теперь важнейший шаг: одних fixtures недостаточно. Добавим события туда, где benchmark действительно делает retrieval и вызывает модель.

### 10.1. Сохраняем traceId в результате

В [Experiment.java](src/main/java/dev/myfrei/repopilot/evaluation/Experiment.java) к Result добавлено последнее поле `String traceId`. Compact constructor нормализует null в пустую строку. Дополнительный старый конструктор делегирует новому с `""`.

Это даёт обратную совместимость с run из 2.4: JSON без traceId можно открыть, но приложение не выдумывает trace задним числом. В UI появится `TRACE_NOT_RECORDED`; для новых стадий нужен новый прогон 3.1.

### 10.2. Передаём сессию явно

У ComparisonEngine появился overload `execute(..., TraceSession trace)`. Старый overload делегирует с disabled session. Передача явным аргументом сохраняет корреляцию в callbacks без предположения, что ThreadLocal переживёт любой переход Reactor между потоками.

Фрагмент ветки RAG из [ComparisonEngine.java](src/main/java/dev/myfrei/repopilot/evaluation/ComparisonEngine.java):

```java
                stage.set(Stage.RETRIEVAL); trace.event(Stage.RETRIEVAL, Status.STARTED);
                timer.searchStarted(); RagSearchResult found;
                try { found = searcher.search(new RagRequest(entry.question(), request.topK(), request.threshold())); }
                finally { timer.searchFinished(); }
                trace.retrieved(found.hits());
                trace.event(Stage.RETRIEVAL, Status.COMPLETED, Map.of("retrieved", found.hits().size()), Map.of("indexFingerprint", found.manifest().fingerprint()), "");
                stage.set(Stage.ASSEMBLY); trace.event(Stage.ASSEMBLY, Status.STARTED);
                retrieved = found.hits().stream().map(hit -> source(hit.source(), hit.text())).toList();
                var plan = planner.plan(entry.question(), List.of(), found);
                sources = plan.report().sources().stream().map(source -> source(source,
                        found.hits().stream().filter(hit -> hit.source().chunkId().equals(source.chunkId())).findFirst().orElseThrow().text())).toList();
                trace.selected(plan.report().sources().stream().map(RagSource::chunkId).toList());
```

Сначала фиксируем найденные hits, затем выбранные planner source IDs. Разметка coverage использует соответствующие raw snapshots, а trace получает только SourceMarks. Технический `indexFingerprint` относится к реально прочитанному manifest.

Не вызываем поиск ещё раз ради диагностики. Повторный query embedding мог бы вернуть другой runtime profile, а второй SQL-запрос — другой snapshot. Диагностика должна наблюдать ту работу, которая породила данный ответ.

У пустого плана отдельная ветка:

```java
                if (plan.references().isEmpty()) {
                    timer.prepared();
                    trace.event(Stage.ASSEMBLY, Status.COMPLETED, Map.of("selected", 0, "estimated", 0, "inputBudget", inputBudget), Map.of(), "");
                    trace.event(Stage.GENERATION, Status.SKIPPED, Map.of(), Map.of(), "NO_EVIDENCE");
                    return new Result(ordinal, entry.id(), repetition, mode, entry.question(), Outcome.NO_EVIDENCE, RagChatService.NO_EVIDENCE,
                            "", timer.finish(), 0, null, null, retrieved.size(), List.of(), EvidenceCoverage.hit(entry, retrieved),
                            EvidenceCoverage.hit(entry, List.of()), "", trace.id());
                }
```

При непустом плане остаётся готовый `QuestionAnswerAdvisor` и `SelectedDocumentsStore` из 2.3. Advisor форматирует уже выбранные источники; это не дополнительный запрос в pgvector. `PromptFingerprint` хеширует `planner.projected(plan)`, а не ранний вопрос без документов. Контрактное IT из 2.3 проверяет совпадение проекции с фактическим HTTP prompt; diagnostic hash сам по себе эту эквивалентность не доказывает.

### 10.3. Наблюдаем streaming

Вокруг `ChatStreamService.observed` добавляем GENERATION и FIRST_TEXT:

```java
            timer.prepared(); timer.modelStarted(); stage.set(Stage.GENERATION); trace.event(Stage.GENERATION, Status.STARTED);
            this.model.observed(messages, advisor, timer::usage).doOnNext(event -> {
                if (event.type().equals("delta")) {
                    timer.delta(event.text()); answer.append(event.text()); deltas.incrementAndGet();
                    if (!event.text().isBlank() && firstText.compareAndSet(false, true)) trace.event(Stage.FIRST_TEXT, Status.COMPLETED);
                }
                if (event.type().equals("completed")) { outcome.set(Outcome.COMPLETED); code.set(""); }
                if (event.type().equals("failed")) { outcome.set(Outcome.FAILED); code.set(event.code()); }
            }).blockLast();
            trace.event(Stage.GENERATION, outcome.get() == Outcome.COMPLETED ? Status.COMPLETED : Status.FAILED,
                    Map.of("deltas", deltas.get(), "answerChars", answer.length()), Map.of(), code.get());
```

Пробел или пустой delta не создаёт FIRST_TEXT. Количество deltas и длина ответа в символах — разные показатели, ни один из них не равен числу токенов. Provider input/output usage остаётся в Result, если runtime его сообщил. Все исходы `failed`, отмена и runtime-ошибка сохраняют явный code; частичный текст не становится успешным ответом.

### 10.4. Завершаем trace в координаторе

В [ExperimentCoordinator.java](src/main/java/dev/myfrei/repopilot/evaluation/ExperimentCoordinator.java) recorder опционален. В `one(...)` создаём context из конкретных условий run, выполняем GUARD, затем engine, затем VERIFY и finish. Фрагмент успешного пути:

```java
        TraceSession trace = this.tracer == null ? TraceSession.disabled() : this.tracer.begin(new DiagnosticTrace.Context(work.run.id(), entry.id(), ordinal, repetition, mode,
                false, conditions.corpusVersion(), conditions.datasetVersion()));
        try {
            trace.event(Stage.GUARD, Status.STARTED);
            this.probe.requireUnchanged(conditions, work.run.request().modes()); trace.event(Stage.GUARD, Status.COMPLETED);
            var row = this.engine.execute(ordinal, entry, repetition, mode, work.run.request(), trace);
            if (work.cancelled.get()) { trace.finish(row.outcome(), row.code()); return row; }
            try {
                trace.event(Stage.VERIFY, Status.STARTED); this.probe.requireUnchanged(conditions, work.run.request().modes());
                trace.event(Stage.VERIFY, Status.COMPLETED); trace.finish(row.outcome(), row.code()); return row;
```

Если модель, корпус или индекс изменились, старый результат не получает статус совместимого качественного измерения. При ошибке VERIFY возвращается FAILED со старым traceId, сохранённым частичным/полным текстом и диагностическим кодом. При отмене дальнейшие попытки не запускаются.

Guard до начала и verify после завершения не являются непрерывным мониторингом runtime в каждый момент. Это граница учебного протокола 2.4. Не запускайте параллельно другие эксперименты и не меняйте модели во время сравнения.

Проверка шага: выполните новый RAG benchmark, откройте конкретный Result и его traceId. В trace должны быть реальные RETRIEVAL → ASSEMBLY → GENERATION → VERIFY → FINISH; если retrieval пуст, GENERATION должен быть SKIPPED. Наличие похожих стадий в SYNTHETIC_LAB этого шага не заменяет.

## 11. Пишем правила, которые не выдают гипотезу за факт

Создайте [DiagnosticReport.java](src/main/java/dev/myfrei/repopilot/diagnostics/DiagnosticReport.java): строка отчёта содержит finding с category, label, facts, hypothesis и nextExperiment. Смысл разделения важнее названий records: факты выводятся из сохранённых сигналов, а гипотеза требует проверки.

[DiagnosticRules.java](src/main/java/dev/myfrei/repopilot/diagnostics/DiagnosticRules.java) выполняет проверки в определённом порядке. Ошибка исполнения имеет приоритет перед оценкой качества. Несовместимый dataset нельзя использовать, чтобы обвинить retrieval. У CHAT документов нет по постановке эксперимента.

| Признаки | Категория | Следующий разумный эксперимент |
| --- | --- | --- |
| FAILED/CANCELLED | EXECUTION_FAILURE | Воспроизвести технический code до изменения prompt |
| Нет совместимых исходных условий/разметки | INCOMPATIBLE_BASELINE | Вернуть исходные данные или сделать новый run |
| CHAT | BASELINE_WITHOUT_DOCUMENTS | Сопоставить тот же вопрос с документными режимами |
| RAG retrieved=false, provided=false | RETRIEVAL_MISS | Проверить кандидатов отдельно от ответа |
| RAG retrieved=true, provided=false | CONTEXT_DROPPED | Проверить selected и бюджет |
| RAG retrieved=false, provided=true | INCONSISTENT_EVIDENCE | Сверить snapshot, ID и разметку |
| provided=true и плохая ручная answer/refusal оценка | GENERATION_REVIEW | Прочитать prompt и ответ; изменить один фактор |
| Факт приемлем, ручная citations ниже 2 | CITATION_REVIEW | Сопоставить утверждения и реальные источники |
| Нет ручного judgment | MANUAL_REVIEW_NEEDED | Оценить смысл ответа и цитат |

Есть также DATA_OR_CONTEXT_REVIEW для отсутствующих/недостаточных сигналов и UNSUPPORTED_ANSWER_REVIEW для сомнительного ответа на UNKNOWN. Полный код полезнее упрощённой таблицы при пограничных состояниях.

Разделение двух частых ошибок:

```java
            if (Boolean.FALSE.equals(row.retrievedEvidenceHit())) return finding(Category.RETRIEVAL_MISS, "Размеченное свидетельство не найдено",
                    List.of("retrievedEvidenceHit=false", "Кандидатов=" + row.retrievedChunks()), "Возможны ошибки chunking, query embedding, фильтра, topK/threshold или самой разметки.", "Посмотреть кандидатов отдельно от генерации; менять один параметр на DEV и проверять ожидаемый фрагмент.");
            if (Boolean.TRUE.equals(row.retrievedEvidenceHit()) && Boolean.FALSE.equals(row.providedEvidenceHit())) return finding(Category.CONTEXT_DROPPED, "Свидетельство потеряно после поиска",
                    List.of("retrievedEvidenceHit=true; providedEvidenceHit=false", "Кандидатов=" + row.retrievedChunks() + "; источников в плане=" + row.sources().size()), "Вероятна потеря при отборе/бюджете, но причина требует проверки IDs и плана.", "Сверить selected markers и omitted/estimated/inputBudget; повторить с контролируемым бюджетом или меньшими чанками.");
        }
```

А при наличии свидетельства отрицательная ручная оценка порождает осторожную гипотезу:

```java
        if (judgment != null && (judgment.answer() < 2 || Boolean.FALSE.equals(judgment.refusalCorrect()))) return finding(Category.UNSUPPORTED_ANSWER_REVIEW, "Проверить ответ без размеченного факта",
                    List.of("UNKNOWN: evidence coverage не применим.", "Есть ручная отрицательная/частичная оценка."), "Ответ мог содержать догадку или неверный отказ; одних счётчиков недостаточно.", "Проверить утверждения ответа по корпусу, зафиксировать отсутствующий факт и обоснованность отказа.");
            return judgedOrPending(judgment);
        }
        if (row.mode() == Mode.RAG) {
            if (row.retrievedEvidenceHit() == null) return finding(Category.DATA_OR_CONTEXT_REVIEW, "Нет подтверждения retrieval stage", List.of("retrievedEvidenceHit=null"), "Данных для разделения поиска и сборки недостаточно.", "Проверить совместимый dataset и повторить запрос с диагностикой стадий.");
            if (Boolean.FALSE.equals(row.retrievedEvidenceHit()) && Boolean.TRUE.equals(row.providedEvidenceHit())) return finding(Category.INCONSISTENT_EVIDENCE, "Сигналы retrieval и prompt противоречат друг другу",
                    List.of("retrievedEvidenceHit=false; providedEvidenceHit=true"), "Возможна ошибка разметки, snapshot или переноса источников.", "Сверить datasetVersion, IDs и raw sources до попытки обвинить модель.");
            if (Boolean.FALSE.equals(row.retrievedEvidenceHit())) return finding(Category.RETRIEVAL_MISS, "Размеченное свидетельство не найдено",
                    List.of("retrievedEvidenceHit=false", "Кандидатов=" + row.retrievedChunks()), "Возможны ошибки chunking, query embedding, фильтра, topK/threshold или самой разметки.", "Посмотреть кандидатов отдельно от генерации; менять один параметр на DEV и проверять ожидаемый фрагмент.");
            if (Boolean.TRUE.equals(row.retrievedEvidenceHit()) && Boolean.FALSE.equals(row.providedEvidenceHit())) return finding(Category.CONTEXT_DROPPED, "Свидетельство потеряно после поиска",
                    List.of("retrievedEvidenceHit=true; providedEvidenceHit=false", "Кандидатов=" + row.retrievedChunks() + "; источников в плане=" + row.sources().size()), "Вероятна потеря при отборе/бюджете, но причина требует проверки IDs и плана.", "Сверить selected markers и omitted/estimated/inputBudget; повторить с контролируемым бюджетом или меньшими чанками.");
        }
        if (!Boolean.TRUE.equals(row.providedEvidenceHit())) return finding(Category.DATA_OR_CONTEXT_REVIEW, "Недостаточно подтверждений о контексте",
                List.of("providedEvidenceHit=" + row.providedEvidenceHit()), "Сигнал отсутствует или не совпал excerpt; это ещё не доказательство ошибки модели.", "Сверить исходный документ, разметку, версию корпуса и фактическую сборку prompt.");
        if (judgment != null && (judgment.answer() < 2 || Boolean.FALSE.equals(judgment.refusalCorrect()))) return finding(Category.GENERATION_REVIEW, "Свидетельство в плане, ответ требует разбора",
                List.of("providedEvidenceHit=true", "Ручная оценка ответа=" + judgment.answer() + "/2"), "Кандидаты причины: инструкция, неоднозначность вопроса, генерация или ошибка ручной/цитатной разметки. Внутреннее решение модели не наблюдается.", "Прочитать фактический prompt и ответ; повторить один вопрос с одним изменением инструкции и вручную проверить результат.");
```

Missing judgment не означает «ответ прошёл проверку». Положительная оценка не превращается в гарантию на все вопросы: NO_DETECTED_ISSUE означает только отсутствие обнаруженной проблемы в данном случае по текущим проверкам.

Не добавляйте глобальное правило «низкий similarity score → неверный ответ». Score относится к выбранной embedding-модели, расстоянию и корпусу; это не вероятность истинности. Не ставьте `answer=2` только по наличию `providedEvidenceHit=true`.

## 12. Сопоставляем run, dataset и trace

DiagnosticService загружает run из того же RunStore, который использовал benchmark. Перед классификацией сравнивает datasetVersion/corpusVersion и точный вопрос текущего case. Если данные изменились, результат получает INCOMPATIBLE_BASELINE. Для расследования нужен исходный dataset либо новый run.

Trace считается доступным только после чтения из TraceStore и сверки:

- runId, ordinal и caseId;
- mode и repetition;
- corpusVersion и datasetVersion;
- `synthetic=false` для настоящего experiment run.

Совпадения одного traceId или caseId недостаточно. Иначе можно случайно приписать источники другого запроса текущему ответу. Несовпадение identity даёт `TRACE_IDENTITY_MISMATCH`, отсутствие/ошибка чтения — `TRACE_UNAVAILABLE`. Отсутствующий trace не отменяет сохранённые результаты 2.4, но ограничивает глубину расследования.

Настоящий diagnostic report не копирует в себя вопрос, ответ и document content: поля sampleQuestion/sampleAnswer пусты. Читайте эти тексты в исходном evaluation run. Только лабораторный отчёт показывает заранее подготовленный fixture-текст и явно помечает происхождение.

Сделайте эту проверку до рисования красивой таблицы в UI. Отчёт с правдоподобными выводами по чужим исходным данным хуже простого сообщения «нет совместимого baseline».

## 13. Воспроизводим три разных сбоя в тренажёре

[DiagnosticLab.java](src/main/java/dev/myfrei/repopilot/diagnostics/DiagnosticLab.java) использует актуальный учебный runbook и первый DEV-кейс. Входы поиска задаёт код; ответы — строки в коде. Настоящие CorpusCatalog/EvidenceCoverage/RagPlanner выполняются без SQL и Ollama.

Три сценария:

| caseId | Кандидаты | Бюджет/selected | Ответ | Ожидаемый разбор |
| --- | --- | --- | --- | --- |
| lab-retrieval | Только нерелевантный ADR | Свидетельства нет | Scripted отказ | RETRIEVAL_MISS |
| lab-budget | Сначала ADR, затем нужный runbook | Хватает на ADR, runbook исключается | Scripted отказ | CONTEXT_DROPPED |
| lab-generation | Нужный runbook | Свидетельство включено | Scripted `9999 [S1]` | GENERATION_REVIEW |

Второй сценарий не подделывает selected-флаг. Tight budget вычисляется из реальной стоимости первого кандидата; планировщик сам отбрасывает следующий. Первый и третий тоже проходят настоящую проверку excerpts, но не тестируют способность retrieval-модели найти документ или chat-модели ответить.

В лабораторном trace GUARD имеет SKIPPED/SYNTHETIC_LAB, GENERATION — SKIPPED/SCRIPTED_RESPONSE, context.synthetic=true. Никакого измеренного времени inference в таком примере нет. Третий сценарий обучает читать сочетание сигналов; он не доказывает поведение какой-либо LLM.

Тренажёр возвращает отдельный report и сохраняет свои traces, но **не сохраняет run в RunStore**. Он не должен попадать в сводку качества и становиться «улучшением модели».

Проверка:

```bash
curl -sS -X POST http://127.0.0.1:8080/api/diagnostics/lab > lab.json
```

Откройте lab.json. Сверьте `origin`, caseId, category и traceAvailable. Возьмите traceId одной строки:

```bash
# Значение — пример формы UUID; замените на ID из ответа lab.
curl -sS http://127.0.0.1:8080/api/diagnostics/traces/00000000-0000-0000-0000-000000000000
```

Если trace не записался из-за прав/диска, отчёт должен честно показать недоступность. Не заменяйте отсутствующий trace картинкой ожидаемых стадий.

## 14. Подключаем API и UI

Зарегистрируйте beans в DiagnosticConfiguration. Контроллер включает только диагностику, не дублирует engine и не запускает второй retrieval.

| Метод и путь | Результат |
| --- | --- |
| POST /api/diagnostics/lab | Три synthetic сценария |
| GET /api/diagnostics/runs/latest | Анализ последнего сохранённого benchmark |
| GET /api/diagnostics/runs/{id} | Анализ выбранного benchmark |
| GET /api/diagnostics/traces/{id} | Метаданные конкретного attempt |
| GET /api/diagnostics/runs/{id}/report.md | Markdown с фактами и незаполненным итоговым выводом |

Полный контроллер:

```java
package dev.myfrei.repopilot.diagnostics;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name = {"repopilot.diagnostics.enabled", "repopilot.evaluation.enabled"}, havingValue = "true")
@RequestMapping("/api/diagnostics")
public class DiagnosticController {
    private final DiagnosticService service;
    private final DiagnosticLab lab;
    private final TraceStore traces;
    public DiagnosticController(DiagnosticService service, DiagnosticLab lab, TraceStore traces) { this.service = service; this.lab = lab; this.traces = traces; }
    @PostMapping("/lab") public DiagnosticReport lab() { return this.lab.replay(); }
    @GetMapping("/runs/latest") public DiagnosticReport latest() { return this.service.latest(); }
    @GetMapping("/runs/{id}") public DiagnosticReport analyze(@PathVariable UUID id) { return this.service.analyze(id); }
    @GetMapping("/traces/{id}") public DiagnosticTrace trace(@PathVariable UUID id) { return this.traces.load(id); }
    @GetMapping(value = "/runs/{id}/report.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> markdown(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=diagnosis-" + id + ".md")
                .body(DiagnosticMarkdown.render(this.service.analyze(id)));
    }
}
```

В [index.html](src/main/resources/static/index.html) появляются панель диагностики, две кнопки загрузки и область trace. JS использует textContent для фактов, гипотез и текста fixtures. Никакой текст модели не вставляется как HTML. UUID в ссылках кодируется, кнопка trace отключена при traceAvailable=false.

`mountDiagnostics` загружает данные по явному действию пользователя. Latest run не запрашивается при каждом открытии страницы. Пока идёт запрос, кнопки заблокированы; счётчик revision не позволяет поздно вернувшемуся trace предыдущего отчёта заменить актуальное представление.

Проверьте вручную три состояния: ещё нет experiments, есть legacy run без trace, есть новый experiment с trace. Затем откройте тренажёр и убедитесь, что заголовок отличается от experiment. Скачивание diagnostic Markdown доступно только для настоящего experiment, потому что lab не хранится как benchmark run.

## 15. Явно выключаем логирование payload в Spring AI

Собственный trace не сериализует тексты. Рядом с ним может существовать логирование framework observations, поэтому в [application.yaml](src/main/resources/application.yaml) явно фиксируем:

```yaml
spring:
  ai:
    chat:
      client:
        observations:
          log-prompt: false
          log-completion: false
      observations:
        log-prompt: false
        log-completion: false
        include-error-logging: false
    vectorstore:
      observations:
        log-query-response: false
```

Настройки и чувствительные данные observations описаны в [официальной документации Spring AI](https://docs.spring.io/spring-ai/reference/observability/index.html). Эта конфигурация относится к framework logging; она не очищает ваши собственные `System.out`, HTTP wire logs или уже сохранённые evaluation artifacts. Не включайте глобальный TRACE/DEBUG для HTTP-клиента ради одного неудачного ответа.

Для содержательного расследования читайте выбранный raw artifact локально и намеренно. В общий Markdown-отчёт переносите минимальные релевантные фрагменты публичного учебного корпуса. Не сдавайте персональные вопросы, пути, переписки и секреты. Встроенные в документы фразы, похожие на инструкции, остаются данными для разбора, а не командами приложению или проверяющему.

## 16. Тестируем факты и границы вывода

Сначала запускаем проверки без реальных весов:

```bash
./mvnw test
npm ci
npm test
```

Затем обязательный профиль с настоящим PostgreSQL/pgvector в Docker:

```bash
./mvnw verify -Ppgvector-it
```

`test` проверяет Java-контракты, локальные HTTP-fixtures и жизненный цикл; pgvector-it проверяет настоящий SQL и integration с Advisor. HTTP Ollama в этих автоматических тестах — fixture, не inference весов. Профиль нельзя считать проверенным, если Docker недоступен и тесты не выполнялись. Результаты именно этого комплекта и ограничения записаны в [VERIFICATION.md](../VERIFICATION.md).

Новые проверки:

| Набор | Что ломаем намеренно |
| --- | --- |
| TraceContractTest | Произвольные поля, raw payload, чужие selected IDs, лимит событий, повторный finish, отказ sink |
| DiagnosticRulesTest | Retrieval miss против context loss, отсутствие judgment, плохие цитаты, UNKNOWN, ошибочный execution |
| DiagnosticLabAndServiceTest | Три fixtures, legacy JSON, несовместимые версии, чужой trace, незаполненный вывод |
| DiagnosticHttpIntegrationTest | REST lab без Ollama/SQL, получение trace, отсутствие lab в списке runs |
| EvaluationHttpIntegrationTest | Streaming stages и отсутствие raw маркеров в HTTP trace и консольном логе |
| PgVectorRagIT | Стадии настоящего SQL/Advisor benchmark, пустая выдача без генерации, неверный HTTP-ответ при имеющемся evidence |
| diagnostics.test.js | Fixture-label, trace state, безопасный DOM, загрузка/ошибки и отсутствие автозапроса |

Пример проверки privacy из теста:

```java
    @Test void sourceMarkersKeepIdsHashesAndSelectionWithoutRawPayload() {
        String payload = "RAW_DOCUMENT_DO_NOT_LOG";
        var source = new RagSource("S1", "c".repeat(64), "runbook", "private/path.md", "Title", "Section", 1, 2, Hashes.sha256(payload), .9);
        var marks = SourceMarks.from(List.of(new RagHit(source, payload)), List.of(source.chunkId()));
        assertThat(marks.getFirst().selected()).isTrue(); assertThat(marks.getFirst().score()).isEqualTo(.9);
        assertThat(JsonMapper.builder().build().writeValueAsString(marks)).doesNotContain(payload, "private/path", "Title");
        assertThatThrownBy(() -> SourceMarks.from(List.of(new RagHit(source, payload)), List.of("d".repeat(64)))).isInstanceOf(IllegalArgumentException.class);
    }
```

Эта проверка имеет смысл, потому что payload и private path реально присутствуют на входе, но отсутствуют в сериализованном marker. Отдельное IT фиксирует консольные trace_event и проверяет, что реальные строковые маркеры вопроса/ответа/документа не утекли туда. Проверять только имя поля `question` было бы недостаточно: текст можно случайно спрятать в code или exception.

Когда тест с неверным ответом `9999` проходит, это означает, что приложение корректно распознало нужный diagnostic pattern на контролируемом HTTP-ответе. Автоматический тест не провёл исследование качества конкретной chat-модели.

## 17. Проводим собственное расследование на DEV

Теперь используем настоящий pipeline и локальные веса. Начните с одного вопроса и RAG, чтобы не путать диагноз с большим сравнением стратегий.

```bash
curl -sS -X POST http://127.0.0.1:8080/api/evaluation/runs \
  -H 'Content-Type: application/json' \
  -d '{"split":"DEV","caseIds":["dev-01"],"modes":["RAG"],"repeats":2,"topK":4,"threshold":0.35,"warmup":true}'
```

POST возвращает 202 и runId; job выполняется в фоне. Пока state=RUNNING, не ставьте оценки и не запускайте второй benchmark. Посмотрите run по ID:

```bash
curl -sS http://127.0.0.1:8080/api/evaluation/runs/00000000-0000-0000-0000-000000000000
```

Подставьте настоящий ID. Выберите ordinal конкретного завершённого результата, прочитайте ответ и sources. Оцените ответ и цитаты независимо. Например, для заведомо неправильного факта с неправильной ссылкой:

```bash
curl -sS -X PUT http://127.0.0.1:8080/api/evaluation/runs/00000000-0000-0000-0000-000000000000/judgments/0 \
  -H 'Content-Type: application/json' \
  -d '{"answer":0,"citations":0,"refusalCorrect":false,"notes":"Указать конкретный неверный факт и источник проверки."}'
```

Это пример формы запроса, не готовая оценка вашего ответа. Не сохраняйте ноль лишь потому, что он показан в лекции. Сверьте ordinal из run.results. Для отсутствующего judgment диагностика оставит ручную проверку незавершённой.

Получите разбор и экспорт:

```bash
curl -sS http://127.0.0.1:8080/api/diagnostics/runs/00000000-0000-0000-0000-000000000000
curl -sS http://127.0.0.1:8080/api/diagnostics/runs/00000000-0000-0000-0000-000000000000/report.md -o diagnosis.md
```

Алгоритм ручного расследования:

1. Зафиксируйте symptom, runId/caseId/ordinal/repetition/mode и traceId.
2. Проверьте versions, model digest, параметры и outcome. При FAILED начните с исполнения.
3. Для RAG сравните candidates и selected, затем сверьте конкретные excerpts с корпусом. Не выводите correctness из одного boolean.
4. Прочитайте ответ и каждую цитату. Отделите неверный факт от неверной ссылки и обоснованного отказа.
5. Выберите одну гипотезу, которую можно опровергнуть. Запишите ожидаемый наблюдаемый эффект.
6. Измените только один фактор на DEV и повторите тот же вопрос с теми же условиями, кроме объявленного изменения.
7. Сравните исходные строки и ручные оценки; запишите, поддержан ли вывод. Сохраните неудачный результат тоже.

Если поменяли dataset/corpus, сравнение версии до и после требует отдельного обоснования. Если поменяли prompt, это тоже условие эксперимента, а не невидимая правка. Параметры выбираются на DEV; HOLDOUT оставляем для финальной проверки выбранной конфигурации.

Не ищите два провала бесконечным перебором порога на HOLDOUT. Если текущие DEV-вопросы все отвечены верно, расширьте DEV осмысленными вопросами или честно зафиксируйте, что реальный провал в доступном прогоне не найден. Контролируемый fixture-разбор допустим как демонстрация метода и явно отличается от исследования весов.

## 18. Типичные затруднения

| Симптом | Что проверить |
| --- | --- |
| «Сначала выполните эксперимент» | Lab не создаёт RunStore entry; запустите настоящий benchmark или откройте lab отдельно |
| TRACE_NOT_RECORDED | Run создан до 3.1 или recorder выключен; новый trace задним числом не появится |
| TRACE_UNAVAILABLE | Файл удалён, запись не удалась или испорчен trace; проверьте директорию и журнал trace_save_failed |
| TRACE_IDENTITY_MISMATCH | Файл не относится к данному run/строке/условиям; не используйте его как доказательство |
| INCOMPATIBLE_BASELINE | Нынешний dataset/corpus отличается от run, либо conditions отсутствуют |
| MANUAL_REVIEW_NEEDED при хорошем coverage | Свидетельство в плане ещё не оценивает смысл ответа и цитат |
| NO_EVIDENCE выглядит очень быстрым | Генерации не было; TTFT/generation latency не равны нулю и не получают кредит в сравнении LLM |
| GENERATION_REVIEW после хорошего retrieval | Прочитайте вопрос, фактическую инструкцию, answer и разметку; модель — одна из гипотез |
| Высокий score у нерелевантного фрагмента | Проверьте encoder input, chunking, фильтр и ожидаемые excerpts; score не вероятность правильности |
| В lab всё воспроизводится, а у модели иначе | Lab задаёт candidates/ответы кодом; это ожидаемая граница тренажёра |

## 19. Самостоятельная работа после лекции

В [homework](../homework/README.md) trace, SourceMarks, DiagnosticRules, API и UI уже реализованы. Новая инженерная задача — **очередь ручного разбора**: объединить повторы одной проблемы по caseId + mode + category, выставить объявленный приоритет и применить limit после группировки. Категории диагностики сами по себе эту очередь не строят.

Вы реализуете только `TriageQueue.build`, остальные слои готовы. Acceptance проверяют новое поведение: группировку, стабильную сортировку, исходные ordinals, счётчики до limit и immutable результат. Не нужно переписывать методы из lecture.

После этого заполните [Failure-Investigation.md](evaluation/Failure-Investigation.md) для двух собственных случаев. Для каждого сдайте наблюдаемые факты, одну проверяемую гипотезу, повторный эксперимент и вывод с ограничениями. Если реальные веса не запускались, отдельно укажите fixture-разбор; не подписывайте его как измеренный результат LLM.

Необязательное исследование: добавить LLM-as-judge как ещё один источник оценки, сравнить его с собственной ручной оценкой нескольких ответов и разобрать расхождения. API Evaluator, RelevancyEvaluator и FactCheckingEvaluator описаны в [Spring AI Evaluation Testing](https://docs.spring.io/spring-ai/reference/api/testing.html). Судья тоже может ошибаться; оценка релевантности не заменяет проверку истинности факта и корректности каждой цитаты. В основной проект этого урока дополнительный judge не включён.

## 20. Самопроверка и источники

Перед переходом дальше объясните без запуска кода:

1. Почему `retrieved=true`, `provided=false` и `provided=true, answer=0` требуют разных экспериментов?
2. Что именно проверяет EvidenceCoverage и какое достаточное свидетельство он способен пропустить?
3. Почему missing judgment не является положительной оценкой?
4. Как связаны traceId, runId, ordinal, repetition и caseId?
5. Почему trace elapsedMs и generation latency — разные числа?
6. Какие части lab настоящие, какие заданы fixtures, и где это видно в trace?
7. Почему GENERATION_REVIEW не доказывает, что модель сознательно проигнорировала документ?
8. Какие данные остаются в evaluation run, даже когда trace не содержит payload?
9. Почему повторный retrieval для «красивого лога» может испортить расследование?
10. Чем локальный trace отличается от интеграции с OpenTelemetry?

Используйте официальные материалы по Spring AI; API примеров сверяйте с закреплённой в проекте версией 2.0.2:

- [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/index.html) — observations компонентов, корреляция и параметры включения содержимого. В примере используется собственный local trace; framework observations не подменяют наши бизнес-стадии.
- [Spring AI Evaluation Testing](https://docs.spring.io/spring-ai/reference/api/testing.html) — интерфейс Evaluator и модельные проверки релевантности/фактов. В основном упражнении сохраняется ручная оценка.
- [Spring AI Retrieval Augmented Generation](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html) — роль QuestionAnswerAdvisor и этапов RAG. В нашем pipeline retrieval/бюджет наблюдаются до форматирования выбранных документов.

Для проверки реализации используйте исходники именно этой папки: [DiagnosticRules](src/main/java/dev/myfrei/repopilot/diagnostics/DiagnosticRules.java), [ComparisonEngine](src/main/java/dev/myfrei/repopilot/evaluation/ComparisonEngine.java), [ExperimentCoordinator](src/main/java/dev/myfrei/repopilot/evaluation/ExperimentCoordinator.java) и [проверки trace](src/test/java/dev/myfrei/repopilot/diagnostics/TraceContractTest.java). Следующий урок разбирает `ChatClientRequest` и собственный Advisor; диагностические признаки сохранятся и помогут проверить результат рефакторинга.
