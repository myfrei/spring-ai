# Урок 2.4. Сравнение CHAT, FULL_CONTEXT и RAG

Мы уже умеем отправлять вопросы локальной LLM, прикладывать весь корпус и искать отдельные чанки. Теперь RepoPilot должен помочь выбрать стратегию на основании воспроизводимого эксперимента. Итог занятия — исполнитель сравнительных прогонов, исходные JSON-результаты, ручные оценки качества и экспорт Markdown-отчёта.

- [Каркас домашнего задания](../homework/README.md).
- [Бланк выбора стратегии](evaluation/Strategy-Decision.md).
- [Техническая проверка и ограничения](../VERIFICATION.md).
- [Предыдущий урок 2.3](../../lesson-2.3/README.md).

Дата проверки: **2026-10-25**. Java 25, Spring Boot 4.1.2, Spring AI 2.0.2, Maven 3.9.11 / Wrapper 3.3.4. SQLite JDBC 3.53.4.0; PostgreSQL JDBC 42.7.14; pgvector/pgvector:0.8.1-pg17; Testcontainers 2.0.5. Node 24 и jsdom 27.4.0 для UI-тестов. Новых библиотек относительно 2.3 нет.

## 1. Что получим

В интерфейсе появится панель «Сравнение CHAT · FULL_CONTEXT · RAG». Она запускает одинаковые вопросы без истории диалога, показывает прогресс, позволяет остановить длинный прогон и оценить ответы вручную. Ответы и sources сохраняются отдельно от SQLite-переписки.

После занятия вы сможете:

1. Отличить качество retrieval от качества ответа и цитат.
2. Разделить DEV и HOLDOUT, зафиксировать параметры до финального теста.
3. Измерить время поиска, первый текст модели и завершение ответа.
4. Не превратить отсутствие вызова LLM или ошибку в «быстрый успешный ответ».
5. Указать число измерений и число ручных оценок рядом со сводкой.
6. Обосновать стратегию по умолчанию с ограничениями вывода.

Ориентир на два часа: постановка эксперимента 20 минут, dataset/evidence 20, runner и время 30, статистика/ручная оценка 20, UI и проверки 20, обсуждение ДЗ 10. Полный прогон настоящих моделей может занять дополнительное время; начните с двух вопросов.

## 2. Три режима и вопрос, который мы проверяем

| Режим | Документы в prompt | Дополнительная работа запроса | Возможная ошибка |
| --- | --- | --- | --- |
| CHAT | Нет | Генерация по вопросу | Догадка о вымышленном проекте |
| FULL_CONTEXT | Весь маленький корпус | Сериализация и проверка бюджета | Не помещается корпус или модель теряет нужную деталь |
| RAG | Выбранные чанки | Query embedding, SQL retrieval, бюджет | Нужное свидетельство не найдено/отброшено |

В программе курса встречается имя NO_CONTEXT. В готовом приложении его соответствует режим CHAT **с пустой историей**. Обычный разговор CHAT с накопленной памятью — другая постановка эксперимента.

Мы сравниваем готовые продуктовые pipeline. Базовая chat-модель, temperature, context/output limits и вопрос одинаковы. Стратегии имеют свои инструкции и способ представления источников; это не эксперимент «изменили ровно одну строку prompt». История всегда пуста, ответы другого режима не переносятся.

FULL_CONTEXT здесь не называется измеренным CAG. В исследовании [Chan et al., Cache-Augmented Generation](https://arxiv.org/abs/2412.15605) рассматривается предварительно подготовленный контекст с runtime cache. Наш стенд отправляет документы и не наблюдает KV-cache или reused tokens. Warm-up и меньшая задержка не доказывают кеширование.

## 3. Быстрый запуск готового примера

Все команды выполняйте из `lessons/lesson-2.4/lecture`. Нужны JDK 25, Docker с Compose, Ollama и Node 24 для UI-тестов. Модели загружаются явной командой; приложение не скачивает веса автоматически.

```bash
java -version
./mvnw test
npm ci
npm test
./mvnw verify -Ppgvector-it
docker compose up -d
ollama pull embeddinggemma:300m
ollama pull qwen2.5:3b
./mvnw spring-boot:run
```

Ollama должен работать: запустите приложение Ollama или `ollama serve`, если runtime ещё не включён. Откройте [RepoPilot](http://127.0.0.1:8080/).

1. В панели документов нажмите «Индексировать».
2. Опубликуйте снимок в pgvector, как в 2.3.
3. Оставьте `dev-01,dev-02`, один повтор и warm-up.
4. Нажмите «Сравнить три режима». Будут три отдельных warm-up и шесть измеряемых запросов.
5. Разверните исходные результаты, прочитайте ответ и фактически переданные sources.
6. Выберите строку, задайте ручные оценки и сохраните их.
7. Скачайте Markdown-отчёт.

Для измерения не отправляйте параллельно обычные chat-запросы, не запускайте другие модели и фоновые нагрузки. Сервер запрещает второй benchmark этого процесса, но не управляет остальными клиентами Ollama на компьютере. Идентичность условий — также правило проведения эксперимента.

По умолчанию UI на 8080, PostgreSQL на 5433; encoder embeddinggemma:300m/768 и chat qwen2.5:3b. Полный корпус мал, но длинные вопросы могут упереться в входное окно chat или encoder. Ошибка остаётся в отчёте.

## 4. Стартовая точка и структура

Готовый проект включает 2.3. Для самостоятельной сборки перенесите Wrapper, POM, `src` и package manifest предыдущего lecture в отдельную папку. Не переносите `target`, `node_modules`, `data`, личную переписку или результаты прошлой индексации.

Новые файлы находятся в `src/main/java/dev/myfrei/repopilot/evaluation`:

| Компонент | Роль |
| --- | --- |
| Experiment / EvaluationCase | Типизированные условия, исходы, оценки и разметка |
| EvaluationDataset | Загрузка 30 кейсов, версия и split |
| EvidenceCoverage | Проверка конкретных размеченных свидетельств |
| RunSchedule | Последовательность вопросов/режимов/повторов |
| CaseMeasurement | Временные границы и provider usage |
| ComparisonEngine | Пустая история, существующие pipeline и streaming |
| ExperimentProbe | Chat digest, corpusVersion, RAG manifest и параметры |
| ExperimentCoordinator | Один активный job, warm-up, прогресс и остановка |
| RunStore | Атомарные JSON-файлы, восстановление после прерывания |
| ComparisonSummary / MarkdownReport | Сводка с явными знаменателями и экспорт |
| EvaluationController / Configuration | API и подключение Spring beans |

Изменение `ChatStreamService` — наблюдатель за настоящими ChatResponse. Старая логика timeout, stop/length и ошибок сохраняется. В обычном test profile новые evaluation beans выключены; интеграционные тесты включают их явно.

## 5. Сначала фиксируем протокол эксперимента

Одна строка результата — один caseId × режим × повтор. Это отдельный запрос, не ход общего разговора. Качество и задержку оцениваем для той же строки, а не соединяем лучший ответ одного прогона с лучшим временем другого.

Порядок работы:

1. Зафиксировать корпус и dataset.
2. На DEV выбрать splitter/topK/threshold, prompt и модель.
3. Выполнить повторные DEV-прогоны и разобрать ошибки.
4. Записать выбранную конфигурацию до обращения к HOLDOUT.
5. Один раз провести итоговую проверку HOLDOUT с этой конфигурацией.
6. Если начали подбирать параметры по HOLDOUT, признать его development-набором и создать новый отложенный набор.

Разделение здесь процедурное. Файл находится в учебном репозитории, поэтому это не система скрытых экзаменационных тестов. Отложены формулировки вопросов того же небольшого корпуса; это не доказательство переноса на новые домены.

## 6. Dataset: 20 DEV и 10 HOLDOUT

Файл: [cases.json](src/main/resources/evaluation/cases.json). Каждый кейс содержит id, split, kind, question, ожидаемый ответ для человека и группы свидетельств. Веса модели и чужой репозиторий для этой практики не нужны.

```json
{
  "id": "dev-01",
  "split": "DEV",
  "kind": "FACT",
  "question": "Какой порт использует TaskShelf?",
  "expectedAnswer": "8091.",
  "evidenceGroups": [
    [{"documentId":"overview","excerpt":"Приложение слушает порт 8091."}],
    [{"documentId":"runbook","excerpt":"с профилем local на порту 8091"}]
  ]
}
```

expectedAnswer — рубрика для ручной проверки. Она никогда не прикладывается к запросу модели. Приведённые в документах факты, конечно, могут совпасть с эталонным ответом: это и есть задача приложения.

Категории:

- FACT: порт, профиль, readiness, команда Atlas, задача TS-42.
- MULTI: относительный путь SQLite вместе с диагностикой, сбор сведений для Atlas.
- UNKNOWN: SLA, авторизация, production или шифрование, о которых корпус не сообщает.
- CONFLICT: пользователь цитирует устаревшую памятку v0, текущие документы говорят иначе.
- LONG_INPUT: тот же предметный вопрос с длинным нерелевантным журналом.

CONFLICT в этом наборе — устаревший фрагмент **в тексте вопроса**, а не одновременно опубликованные противоречащие друг другу документы. LONG_INPUT увеличивает вход запроса; размер основного корпуса не меняется. Это контролируемые учебные fixtures, а не полный benchmark всех версий и размеров документации.

Файл загружается с лимитом размера, все вопросы не длиннее 4000 символов. Loader проверяет уникальность ID, распределение 20/10 и наличие каждого размеченного excerpt в исходном документе. SHA-256 исходного JSON становится datasetVersion. Изменение разметки тоже меняет версию.

```java
package dev.myfrei.repopilot.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashSet;
import dev.myfrei.repopilot.context.CorpusCatalog;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.json.JsonMapper;

public class EvaluationDataset {
    private final List<EvaluationCase> cases;
    private final String version;
    public EvaluationDataset(ResourceLoader loader, CorpusCatalog corpus) {
        try (var input = loader.getResource("classpath:evaluation/cases.json").getInputStream()) {
            byte[] bytes = input.readNBytes(131073);
            if (bytes.length > 131072) throw new IllegalArgumentException("Dataset too large");
            this.version = Hashes.sha256(new String(bytes, StandardCharsets.UTF_8));
            this.cases = List.of(JsonMapper.builder().build().readValue(bytes, EvaluationCase[].class));
            var ids = new HashSet<String>();
            for (var entry : this.cases) {
                if (!entry.id().matches("(dev|holdout)-[0-9]{2}") || !ids.add(entry.id())
                        || entry.split() == null || !entry.id().startsWith(entry.split() == Split.DEV ? "dev-" : "holdout-")
                        || entry.question().isBlank() || entry.question().length() > 4000 || entry.expectedAnswer().isBlank()) throw new IllegalArgumentException("Invalid case");
                for (var group : entry.evidenceGroups()) {
                    if (group.isEmpty()) throw new IllegalArgumentException("Empty evidence group");
                    for (var evidence : group) {
                        if (evidence.excerpt().isBlank() || !EvidenceCoverage.normalize(corpus.document(evidence.documentId()).text())
                                .contains(EvidenceCoverage.normalize(evidence.excerpt()))) throw new IllegalArgumentException("Gold evidence absent from corpus: " + entry.id());
                    }
                }
            }
            if (cases.stream().filter(entry -> entry.split() == Split.DEV).count() != 20
                    || cases.stream().filter(entry -> entry.split() == Split.HOLDOUT).count() != 10) throw new IllegalArgumentException("Require 20 DEV and 10 HOLDOUT");
        } catch (IOException error) { throw new IllegalStateException("Cannot load evaluation dataset", error); }
    }
    public String version() { return this.version; }
    public List<EvaluationCase> cases(Split split) { return this.cases.stream().filter(entry -> entry.split() == split).toList(); }
    public List<EvaluationCase> select(Request request) {
        var subset = cases(request.split());
        if (request.caseIds().isEmpty()) return subset;
        var selected = subset.stream().filter(entry -> request.caseIds().contains(entry.id())).toList();
        if (selected.size() != request.caseIds().size()) throw new IllegalArgumentException("Case ID outside selected split");
        return selected;
    }
}
```

Если корпус изменился, отредактируйте разметку осознанно и пересоздайте RAG-индекс. Не заменяйте ожидаемые ответы результатами модели, чтобы поднять её оценку.

## 7. Автоматически проверяем свидетельства, вручную — смысл ответа

`evidenceGroups` — OR альтернативных достаточных наборов; внутри набора AND условий. Условие требует совпадающий documentId и присутствие нормализованного excerpt хотя бы в одном source.

Например, порт можно подтвердить обзором **или** runbook. Для ответа о пути и диагностике размеченная группа может требовать сведения из двух документов. Строки нормализуются по Unicode NFC и пробельным символам.

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

Это узкая проверка заранее размеченных цитатных свидетельств:

- `retrievedEvidenceHit` относится к кандидатам RAG до бюджетного отбора.
- `providedEvidenceHit` относится к источникам, фактически переданным модели.
- У answerable-кейса CHAT получает false: документы не прикладывались.
- У UNKNOWN обе метрики не применимы: null.

Слово false не означает, что контекста семантически недостаточно. Проверка не распознаёт парафразы и не склеивает части excerpt из нескольких разрезанных чанков. True тоже не доказывает, что LLM воспользовалась свидетельством правильно. Это не универсальный recall, faithfulness или «вероятность верного ответа».

## 8. Описываем условия и исходы

Основные типы собраны в Experiment. Request ограничивает повторы 1..3, topK 1..8, threshold 0..1, запрещает дубли modes/caseIds. Loader дополнительно проверяет принадлежность caseIds выбранному split.

```java
package dev.myfrei.repopilot.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.myfrei.repopilot.rag.RagManifest;

public record Experiment(UUID id, String startedAt, String finishedAt, State state, Request request,
        Conditions conditions, int planned, List<Result> warmups, List<Result> results,
        Map<Integer, Judgment> judgments, String message) {
    public Experiment { warmups = List.copyOf(warmups); results = List.copyOf(results); judgments = Map.copyOf(judgments); }
    public enum Mode { CHAT, FULL_CONTEXT, RAG }
    public enum Split { DEV, HOLDOUT }
    public enum State { RUNNING, COMPLETED, CANCELLED, FAILED, INTERRUPTED }
    public enum Outcome { COMPLETED, NO_EVIDENCE, FAILED, CANCELLED }
    public record Request(Split split, List<String> caseIds, List<Mode> modes, Integer repeats,
            Integer topK, Double threshold, Boolean warmup) {
        public Request {
            if (split == null) split = Split.DEV;
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
            modes = modes == null ? List.of(Mode.CHAT, Mode.FULL_CONTEXT, Mode.RAG) : List.copyOf(modes);
            if (repeats == null) repeats = 1; if (topK == null) topK = 4;
            if (threshold == null) threshold = .35; if (warmup == null) warmup = true;
            if (modes.isEmpty() || modes.size() > 3 || modes.stream().distinct().count() != modes.size()
                    || caseIds.size() > 30 || caseIds.stream().distinct().count() != caseIds.size()
                    || repeats < 1 || repeats > 3 || topK < 1 || topK > 8
                    || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) throw new IllegalArgumentException("Invalid experiment request");
        }
    }
    public record ChatProfile(String model, String digest, double temperature, int contextWindow, int outputLimit, String systemPromptHash) { }
    public record Conditions(String datasetVersion, String corpusVersion, ChatProfile chat, RagManifest rag,
            String estimator, int inputBudget, String pipelineVersion, String cacheEvidence, String orderPolicy) { }
    public record Source(String label, String documentId, String path, String chunkId, String text) { }
    public record Timing(double totalMs, double preparationMs, double retrievalMs, Double generationMs,
            Double endToEndTtftMs, Double modelTtftMs) { }
    public record Result(int ordinal, String caseId, int repetition, Mode mode, String question, Outcome outcome,
            String answer, String code, Timing timing, Integer estimatedInputTokens, Integer providerInputTokens,
            Integer providerOutputTokens, int retrievedChunks, List<Source> sources, Boolean retrievedEvidenceHit,
            Boolean providedEvidenceHit, String promptHash) {
        public Result { sources = List.copyOf(sources); }
    }
    public record Judgment(int answer, int citations, Boolean refusalCorrect, String notes, String reviewedAt) {
        public Judgment {
            if (answer < 0 || answer > 2 || citations < 0 || citations > 2 || notes == null || notes.length() > 2000) throw new IllegalArgumentException("Invalid manual judgment");
        }
    }
}
```

`State.COMPLETED` означает, что все запланированные попытки обработаны. Среди них могут быть `Outcome.FAILED`: успех всего прогона нельзя читать как отсутствие ошибок модели.

Outcome.NO_EVIDENCE — программное сообщение без chat inference; encoder и SQL уже могли работать. Outcome.FAILED сохраняет частичный ответ, код и доступные метрики. Не превращаем OUTPUT_LIMIT в корректный законченный ответ.

В conditions фиксируются datasetVersion, corpusVersion, model tag/digest, явные temperature/context/output limits, hash базового system prompt, бюджет, pipelineVersion, manifest pgvector и политика порядка. topK/threshold и repeats входят в Request. Дополнительные runtime options, версию Ollama, оборудование и нагрузку укажите в бланке: встроенный manifest не описывает весь компьютер.

## 9. Строим расписание без общей истории

Для каждого вопроса запускаем каждый выбранный режим. Порядок режимов циклически сдвигается между вопросами и повторами:

```text
dev-01 / repeat 1: CHAT → FULL_CONTEXT → RAG
dev-02 / repeat 1: FULL_CONTEXT → RAG → CHAT
dev-01 / repeat 2: FULL_CONTEXT → RAG → CHAT
```

Это детерминированная ротация, которая уменьшает привязку режима к одной позиции. Она не устраняет все эффекты прогрева и дрейфа нагрузки. Порядок кейсов остаётся каноническим; случайного перемешивания или статистического ослепления здесь нет.

```java
package dev.myfrei.repopilot.evaluation;

import java.util.ArrayList;
import java.util.List;
import dev.myfrei.repopilot.evaluation.Experiment.*;
public final class RunSchedule {
    private RunSchedule() { }
    public record Task(int ordinal, EvaluationCase entry, int repetition, Mode mode) { }
    public static List<Task> build(List<EvaluationCase> cases, Request request) {
        var tasks = new ArrayList<Task>();
        for (int repetition = 1; repetition <= request.repeats(); repetition++) {
            for (int i = 0; i < cases.size(); i++) {
                int offset = (i + repetition - 1) % request.modes().size();
                for (int j = 0; j < request.modes().size(); j++) {
                    tasks.add(new Task(tasks.size(), cases.get(i), repetition, request.modes().get((j + offset) % request.modes().size())));
                }
            }
        }
        return List.copyOf(tasks);
    }
}
```

Warm-up выполняется отдельно на первом DEV-кейсе для каждого выбранного режима, в том числе перед HOLDOUT. Он не входит в measured results и не подменяет отложенные вопросы. Не называйте первый measured request «cold»: мы не выгружаем модель и не наблюдаем состояние KV-cache.

## 10. Определяем время до написания кода

Используем `System.nanoTime()` для интервалов, а Instant — только для временных меток файла. Разность wall-clock часов может измениться при синхронизации системного времени. [Java 25 System API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/System.html#nanoTime()).

| Поле | Граница измерения |
| --- | --- |
| totalMs | От начала обработки case до завершения/ошибки потока |
| preparationMs | Сборка контекста, retrieval, budget, hash до вызова модели |
| retrievalMs | Вызов RagSearcher: profile checks, query embedding и SQL; не один SQL-запрос |
| generationMs | ChatClient/advisor/HTTP/LLM до конца потока |
| endToEndTtftMs | От начала case до первого непустого текста модели |
| modelTtftMs | От отправной точки вызова ChatClient до первого непустого текста |

Это TTFT, наблюдаемый **Java-приложением**. Доставка через SSE и отрисовка в браузере не включены. Это не чистое GPU-время. Identity guards, сохранение отчёта и ручная оценка идут вне этих интервалов; продолжительность всего job будет больше суммы row totalMs.

При NO_EVIDENCE TTFT/generation = null, а не 0. Нулевое retrievalMs у CHAT означает отсутствие этого этапа. Пробельная delta не считается первым полезным текстом.

```java
package dev.myfrei.repopilot.evaluation;

import java.util.function.LongSupplier;
import org.springframework.ai.chat.model.ChatResponse;
import dev.myfrei.repopilot.evaluation.Experiment.Timing;
public class CaseMeasurement {
    private final LongSupplier clock;
    private final long start;
    private long prepared;
    private long retrievalStart;
    private long retrievalNanos;
    private Long modelStart;
    private Long firstText;
    private Integer inputTokens;
    private Integer outputTokens;
    public CaseMeasurement(LongSupplier clock) { this.clock = clock; this.start = clock.getAsLong(); this.prepared = this.start; }
    public synchronized void searchStarted() { this.retrievalStart = this.clock.getAsLong(); }
    public synchronized void searchFinished() { this.retrievalNanos += this.clock.getAsLong() - this.retrievalStart; }
    public synchronized void prepared() { this.prepared = this.clock.getAsLong(); }
    public synchronized void modelStarted() { this.modelStart = this.clock.getAsLong(); }
    public synchronized void delta(String text) { if (this.modelStart != null && this.firstText == null && text != null && !text.isBlank()) this.firstText = this.clock.getAsLong(); }
    public synchronized void usage(ChatResponse response) {
        var usage = response.getMetadata().getUsage();
        if (usage != null) {
            if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0) this.inputTokens = usage.getPromptTokens();
            if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0) this.outputTokens = usage.getCompletionTokens();
        }
    }
    public synchronized Integer inputTokens() { return this.inputTokens; }
    public synchronized Integer outputTokens() { return this.outputTokens; }
    public synchronized Timing finish() {
        long end = this.clock.getAsLong();
        return new Timing(ms(end - this.start), ms((this.modelStart == null ? end : this.prepared) - this.start), ms(this.retrievalNanos),
                this.modelStart == null ? null : ms(end - this.modelStart), this.firstText == null ? null : ms(this.firstText - this.start),
                this.firstText == null ? null : ms(this.firstText - this.modelStart));
    }
    private static double ms(long nanos) { return Math.max(0, nanos) / 1_000_000.0; }
}
```

`estimatedInputTokens` остаётся оценкой utf8-div3-overhead12-v1 из 2.1, а providerInputTokens/providerOutputTokens берутся из реального ChatResponse usage. Пропущенные/нулевые счётчики записываются как null, не заменяются оценкой. Для непустого успешного chat request нулевой input count здесь трактуется как отсутствие измерения.

Ollama возвращает duration и eval counters в ответе; они не равны времени, которое наблюдает приложение. В этом срезе duration из provider не агрегируется. См. [Ollama Chat API](https://docs.ollama.com/api/chat).

## 11. Добавляем наблюдатель ChatResponse

В ChatStreamService появилась перегрузка `observed(messages, advisor, observer)`. Она использует ту же обработку streaming, что и чат. Перед обработкой delta/finish вызывается consumer, которому доступны metadata и usage.

```java
public Flux<ChatStreamEvent> observed(List<Message> messages, Advisor advisor,
        Consumer<ChatResponse> observer) {
    var snapshot = List.copyOf(messages);
    return streamResponses(() -> {
        var request = this.client.prompt().messages(snapshot);
        if (advisor != null) request.advisors(advisor);
        return request.stream().chatResponse();
    }, observer);
}
```

Внутри общей streamResponses добавлено `.doOnNext(observer)` перед проверкой finish reason. Остальные события/ошибки и старые endpoints не меняются. Точные provider counts доступны только при их наличии в ответе; тестовый HTTP-стенд проверяет этот путь без настоящих весов.

## 12. Повторно используем существующие pipeline

ComparisonEngine для CHAT/FULL_CONTEXT вызывает ContextAssembler с `List.of()` вместо истории. Для RAG использует RagSearcher и RagPlanner из 2.3, затем тот же QuestionAnswerAdvisor с фиксированным набором выбранных Documents.

Не запускаем новый разговор в SQLite для каждого измерения. Это и быстрее, и исключает случайное попадание предыдущего ответа в память. Обычная переписка продолжает работать отдельно.

```java
package dev.myfrei.repopilot.evaluation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import dev.myfrei.repopilot.chat.ChatStreamService;
import dev.myfrei.repopilot.context.*;
import dev.myfrei.repopilot.conversation.ConversationProblem;
import dev.myfrei.repopilot.ingestion.Hashes;
import dev.myfrei.repopilot.rag.*;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

public class ComparisonEngine {
    private final CorpusCatalog corpus;
    private final ContextAssembler assembler;
    private final ObjectProvider<RagSearcher> searchers;
    private final ObjectProvider<RagPlanner> planners;
    private final ChatStreamService model;
    public ComparisonEngine(CorpusCatalog corpus, ContextAssembler assembler, ObjectProvider<RagSearcher> searchers,
            ObjectProvider<RagPlanner> planners, ChatStreamService model) {
        this.corpus = corpus; this.assembler = assembler; this.searchers = searchers; this.planners = planners; this.model = model;
    }
    public Result execute(int ordinal, EvaluationCase entry, int repetition, Mode mode, Request request) {
        var timer = new CaseMeasurement(System::nanoTime);
        var answer = new StringBuilder(); var outcome = new AtomicReference<>(Outcome.FAILED); var code = new AtomicReference<>("INCOMPLETE");
        List<Source> sources = List.of(); List<Source> retrieved = List.of(); Integer estimated = null; String promptHash = "";
        try {
            List<Message> messages; List<Message> expanded; Advisor advisor = null;
            if (mode == Mode.RAG) {
                RagSearcher searcher = this.searchers.getIfAvailable(); RagPlanner planner = this.planners.getIfAvailable();
                if (searcher == null || planner == null) throw new EvaluationProblem("RAG_DISABLED", "RAG отключён.");
                timer.searchStarted(); RagSearchResult found;
                try { found = searcher.search(new RagRequest(entry.question(), request.topK(), request.threshold())); }
                finally { timer.searchFinished(); }
                retrieved = found.hits().stream().map(hit -> source(hit.source(), hit.text())).toList();
                var plan = planner.plan(entry.question(), List.of(), found);
                sources = plan.report().sources().stream().map(source -> source(source,
                        found.hits().stream().filter(hit -> hit.source().chunkId().equals(source.chunkId())).findFirst().orElseThrow().text())).toList();
                estimated = plan.report().estimatedInputTokens(); messages = plan.baseMessages(); expanded = planner.projected(plan);
                if (plan.references().isEmpty()) {
                    timer.prepared();
                    return new Result(ordinal, entry.id(), repetition, mode, entry.question(), Outcome.NO_EVIDENCE, RagChatService.NO_EVIDENCE,
                            "", timer.finish(), 0, null, null, retrieved.size(), List.of(), EvidenceCoverage.hit(entry, retrieved),
                            EvidenceCoverage.hit(entry, List.of()), "");
                }
                advisor = QuestionAnswerAdvisor.builder(new SelectedDocumentsStore(entry.question(), plan.references()))
                        .promptTemplate(RagPrompt.template()).searchRequest(SearchRequest.builder().topK(plan.references().size()).build()).build();
            } else {
                var plan = this.assembler.assemble(entry.question(), ContextMode.valueOf(mode.name()), List.of(), this.corpus.snapshot());
                messages = plan.messages(); expanded = messages; estimated = plan.report().estimatedInputTokens();
                sources = plan.report().sources().stream().map(source -> new Source(source.id(), source.id(), source.path(), "", this.corpus.document(source.id()).text())).toList();
            }
            promptHash = PromptFingerprint.of(expanded);
            timer.prepared(); timer.modelStarted();
            this.model.observed(messages, advisor, timer::usage).doOnNext(event -> {
                if (event.type().equals("delta")) { timer.delta(event.text()); answer.append(event.text()); }
                if (event.type().equals("completed")) { outcome.set(Outcome.COMPLETED); code.set(""); }
                if (event.type().equals("failed")) { outcome.set(Outcome.FAILED); code.set(event.code()); }
            }).blockLast();
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted()) { outcome.set(Outcome.CANCELLED); code.set("CANCELLED"); }
            else { outcome.set(Outcome.FAILED); code.set(error instanceof ConversationProblem problem ? problem.code() : "EXPERIMENT_ERROR"); }
        }
        return new Result(ordinal, entry.id(), repetition, mode, entry.question(), outcome.get(), answer.toString(), code.get(), timer.finish(),
                estimated, timer.inputTokens(), timer.outputTokens(), retrieved.size(), sources, mode == Mode.RAG ? EvidenceCoverage.hit(entry, retrieved) : null,
                EvidenceCoverage.hit(entry, sources), promptHash);
    }
    private static Source source(RagSource source, String text) { return new Source(source.label(), source.documentId(), source.path(), source.chunkId(), text); }
}
```

promptHash строится по упорядоченным ролям и текстам через length-prefixed Hashes.identity. Не используем произвольный порядок ключей Map в JSON как канонический формат hash.

Для RAG fingerprint считается по **проекции окончательной expansion advisor**, а не только по вопросу. Предыдущий тест 2.3 и новый benchmark integration проверяют настоящий outbound prompt. Hash помогает сопоставить входы, но сам не оценивает их качество.

## 13. Проверяем неизменность модели и индекса

ExperimentProbe получает digest chat tag через `/api/tags`, сохраняет условия и проверяет их до/после каждого case. Если выбран RAG, нужен активный manifest для той же corpusVersion. RagSearcher отдельно проверяет embedding profile.

Изменившиеся условия инвалидируют текущую строку, сохраняют её raw answer и останавливают оставшиеся запросы. Если последующая проверка не удалась, строка получает CONDITIONS_UNVERIFIED и тоже не участвует как успешное измерение. Прогон не должен молча смешать разные model/index revisions.

Проверки до/после не являются атомарным snapshot всего Ollama: короткую смену и возврат tag между ними они не гарантируют обнаружить. Во время эксперимента не меняйте модели или данные.

## 14. Запускаем длительную работу как job

POST возвращает 202 с UUID, обработка идёт на виртуальном потоке Java 25. Один coordinator допускает один активный эксперимент. Внутри job запросы последовательны: параллельный benchmark сам изменил бы задержки очередью модели.

```java
package dev.myfrei.repopilot.evaluation;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.myfrei.repopilot.conversation.ConversationProblem;
import dev.myfrei.repopilot.evaluation.Experiment.*;

public class ExperimentCoordinator implements AutoCloseable {
    private final EvaluationDataset dataset;
    private final ExperimentProbe probe;
    private final ComparisonEngine engine;
    private final RunStore store;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private Active active;
    public ExperimentCoordinator(EvaluationDataset dataset, ExperimentProbe probe, ComparisonEngine engine, RunStore store) {
        this.dataset = dataset; this.probe = probe; this.engine = engine; this.store = store; this.store.recoverInterrupted();
    }
    public synchronized Experiment start(Request request) {
        if (this.active != null) throw new EvaluationProblem("EXPERIMENT_BUSY", "Другой эксперимент ещё выполняется. Дождитесь завершения или остановите его.");
        var cases = this.dataset.select(request);
        var initial = new Experiment(UUID.randomUUID(), Instant.now().toString(), "", State.RUNNING, request, null,
                cases.size() * request.repeats() * request.modes().size(), List.of(), List.of(), Map.of(), "");
        this.store.save(initial); var work = new Active(initial); this.active = work;
        this.workers.submit(() -> execute(work, cases)); return initial;
    }
    public synchronized void cancel(UUID id) {
        if (this.active == null || !this.active.run.id().equals(id)) throw new EvaluationProblem("NOT_RUNNING", "Этот эксперимент уже не выполняется.");
        this.active.cancelled.set(true); if (this.active.thread != null) this.active.thread.interrupt();
    }
    public synchronized Experiment judge(UUID id, int ordinal, Judgment judgment) {
        var run = this.store.load(id);
        if (run.state() == State.RUNNING) throw new EvaluationProblem("EXPERIMENT_BUSY", "Оцените ответы после остановки/завершения эксперимента.");
        var row = run.results().stream().filter(result -> result.ordinal() == ordinal).findFirst()
                .orElseThrow(() -> new EvaluationProblem("NOT_FOUND", "Строка результата не найдена."));
        if (row.outcome() != Outcome.COMPLETED && row.outcome() != Outcome.NO_EVIDENCE) throw new IllegalArgumentException("Cannot judge failed request");
        var judgments = new LinkedHashMap<>(run.judgments());
        judgments.put(ordinal, new Judgment(judgment.answer(), judgment.citations(), judgment.refusalCorrect(), judgment.notes(), Instant.now().toString()));
        var updated = new Experiment(run.id(), run.startedAt(), run.finishedAt(), run.state(), run.request(), run.conditions(), run.planned(), run.warmups(), run.results(), judgments, run.message());
        this.store.save(updated); return updated;
    }
    private void execute(Active work, List<EvaluationCase> cases) {
        work.thread = Thread.currentThread();
        try {
            checkCancellation(work);
            var conditions = this.probe.capture(work.run.request().modes());
            work.run = new Experiment(work.run.id(), work.run.startedAt(), "", State.RUNNING, work.run.request(), conditions,
                    work.run.planned(), List.of(), List.of(), Map.of(), ""); this.store.save(work.run);
            if (work.run.request().warmup()) {
                var warm = this.dataset.cases(Split.DEV).getFirst(); int ordinal = -1;
                for (Mode mode : work.run.request().modes()) {
                    checkCancellation(work); var row = one(work, ordinal--, warm, 0, mode); append(work, row, true);
                    if (row.outcome() == Outcome.FAILED) throw new EvaluationProblem("WARMUP_FAILED", "Warm-up завершился ошибкой " + row.code() + ". Измерения не начаты.");
                }
            }
            for (var task : RunSchedule.build(cases, work.run.request())) {
                checkCancellation(work);
                var row = one(work, task.ordinal(), task.entry(), task.repetition(), task.mode()); append(work, row, false);
                if (Set.of("EXPERIMENT_CHANGED", "MODEL_CHANGED", "INDEX_CHANGED", "STALE_INDEX", "INDEX_PROFILE_MISMATCH", "CONDITIONS_UNVERIFIED").contains(row.code())) {
                    throw new EvaluationProblem(row.code(), "Условия запроса изменились или не подтверждены. Прогон остановлен, исходные результаты сохранены.");
                }
            }
            checkCancellation(work); finish(work, State.COMPLETED, "Все запланированные запросы обработаны; наличие ошибок смотрите в строках.");
        } catch (RuntimeException error) {
            boolean cancelled = work.cancelled.get() || Thread.currentThread().isInterrupted();
            Thread.interrupted();
            finish(work, cancelled ? State.CANCELLED : State.FAILED, cancelled ? "Остановлено пользователем; оставшиеся запросы не запускались."
                    : error instanceof ConversationProblem problem ? problem.code() + ": " + problem.getMessage() : "EXPERIMENT_ERROR: эксперимент остановлен.");
        } finally { synchronized (this) { if (this.active == work) this.active = null; } }
    }
    private Result one(Active work, int ordinal, EvaluationCase entry, int repetition, Mode mode) {
        this.probe.requireUnchanged(work.run.conditions(), work.run.request().modes());
        var row = this.engine.execute(ordinal, entry, repetition, mode, work.run.request());
        if (work.cancelled.get()) return row;
        try { this.probe.requireUnchanged(work.run.conditions(), work.run.request().modes()); return row; }
        catch (RuntimeException error) {
            return new Result(row.ordinal(), row.caseId(), row.repetition(), row.mode(), row.question(), Outcome.FAILED, row.answer(),
                    error instanceof EvaluationProblem problem && problem.code().equals("EXPERIMENT_CHANGED") ? "EXPERIMENT_CHANGED" : "CONDITIONS_UNVERIFIED", row.timing(), row.estimatedInputTokens(), row.providerInputTokens(), row.providerOutputTokens(),
                    row.retrievedChunks(), row.sources(), row.retrievedEvidenceHit(), row.providedEvidenceHit(), row.promptHash());
        }
    }
    private void append(Active work, Result row, boolean warmup) {
        var warmups = new ArrayList<>(work.run.warmups()); var results = new ArrayList<>(work.run.results());
        if (warmup) warmups.add(row); else results.add(row);
        work.run = new Experiment(work.run.id(), work.run.startedAt(), "", State.RUNNING, work.run.request(), work.run.conditions(),
                work.run.planned(), warmups, results, Map.of(), ""); this.store.save(work.run);
    }
    private void finish(Active work, State state, String message) {
        work.run = new Experiment(work.run.id(), work.run.startedAt(), Instant.now().toString(), state, work.run.request(), work.run.conditions(),
                work.run.planned(), work.run.warmups(), work.run.results(), work.run.judgments(), message); this.store.save(work.run);
    }
    private static void checkCancellation(Active work) { if (work.cancelled.get() || Thread.currentThread().isInterrupted()) throw new EvaluationProblem("CANCELLED", "Остановка."); }
    @Override public synchronized void close() { if (this.active != null) this.active.cancelled.set(true); this.workers.shutdownNow(); }
    private static class Active {
        volatile Experiment run; volatile Thread thread; final AtomicBoolean cancelled = new AtomicBoolean();
        Active(Experiment run) { this.run = run; }
    }
}
```

Остановка ставит флаг и прерывает worker. Оставшиеся задачи не запускаются, полученные строки сохраняются. Отправленный запрос может уже успеть завершиться до отмены; его завершённый ответ остаётся в отчёте. Не рассчитывайте, что cancel мгновенно освобождает VRAM runtime.

## 15. Сохраняем данные до вычисления красивой сводки

RunStore пишет целый run в JSON через временный файл и ATOMIC_MOVE. При чтении после перезапуска экземпляра доступны те же результаты и ручные оценки. Незавершённые RUNNING-файлы при старте одного процесса становятся INTERRUPTED, без автоматического повтора model calls.

```yaml
repopilot:
  evaluation:
    enabled: true
    directory: ${REPOPILOT_EVALUATION_DIR:./data/evaluation}
```

Файлы `run-<uuid>.json` находятся относительно рабочей директории по умолчанию. Один процесс владеет одним каталогом отчётов. SQLite-история, JSON ingestion и PostgreSQL-индекс остаются отдельными хранилищами. Несколько процессов с общим evaluation directory этим примером не координируются.

API сохраняет raw question, answer, sources, ошибки и метрики. Не ограничивайтесь скриншотом со средними значениями: исходные строки нужны для проверки вывода.

## 16. Правильные знаменатели в сводке

Время генерации считаем только для Outcome.COMPLETED. Ошибки, отмены и NO_EVIDENCE показываем отдельными счётчиками. В каждой distribution есть n, median и nearest-rank p95. При пустом наборе значение null, а не 0.

Например, если RAG вернул пять NO_EVIDENCE и один правильный ответ, его один быстрый TTFT нельзя сравнить с шестью успешными ответами FULL_CONTEXT без учёта отказов. Исключение ошибок из latency не делает метод лучше: сначала смотрите покрытие попыток и исходы.

Качество усредняется только по явно сохранённым Judgment. Ноль означает оценку «неверно», null — отсутствие оценки. Warm-up исключён из всей сводки.

```java
package dev.myfrei.repopilot.evaluation;

import java.util.List;
import dev.myfrei.repopilot.evaluation.Experiment.*;
public final class ComparisonSummary {
    private ComparisonSummary() { }
    public record Distribution(int n, Double median, Double p95) { }
    public record Rate(int n, int hits, Double fraction) { }
    public record Group(Mode mode, int attempted, int completed, int noEvidence, int failed, int cancelled, int judged,
            Double meanAnswerScore, Double meanCitationScore, Distribution totalMs, Distribution modelTtftMs,
            Distribution endToEndTtftMs, Distribution estimatedInputTokens, Rate providedEvidence) { }
    public static List<Group> summarize(Experiment run) {
        return run.request().modes().stream().map(mode -> {
            var rows = run.results().stream().filter(row -> row.mode() == mode).toList();
            var completed = rows.stream().filter(row -> row.outcome() == Outcome.COMPLETED).toList();
            var eligible = rows.stream().filter(row -> row.outcome() == Outcome.COMPLETED || row.outcome() == Outcome.NO_EVIDENCE).toList();
            var judgments = eligible.stream().filter(row -> run.judgments().containsKey(row.ordinal())).map(row -> run.judgments().get(row.ordinal())).toList();
            var coverage = eligible.stream().filter(row -> row.providedEvidenceHit() != null).toList();
            int hits = (int) coverage.stream().filter(row -> row.providedEvidenceHit()).count();
            return new Group(mode, rows.size(), completed.size(), count(rows, Outcome.NO_EVIDENCE), count(rows, Outcome.FAILED), count(rows, Outcome.CANCELLED), judgments.size(),
                    judgments.isEmpty() ? null : judgments.stream().mapToInt(Judgment::answer).average().orElseThrow(),
                    judgments.isEmpty() ? null : judgments.stream().mapToInt(Judgment::citations).average().orElseThrow(),
                    distribution(completed.stream().map(row -> row.timing().totalMs()).toList()),
                    distribution(completed.stream().map(row -> row.timing().modelTtftMs()).toList()),
                    distribution(completed.stream().map(row -> row.timing().endToEndTtftMs()).toList()),
                    distribution(completed.stream().map(row -> row.estimatedInputTokens() == null ? null : row.estimatedInputTokens().doubleValue()).toList()),
                    new Rate(coverage.size(), hits, coverage.isEmpty() ? null : (double) hits / coverage.size()));
        }).toList();
    }
    public static Distribution distribution(List<Double> values) {
        var sorted = values.stream().filter(java.util.Objects::nonNull).filter(Double::isFinite).sorted().toList();
        int size = sorted.size(); if (size == 0) return new Distribution(0, null, null);
        double median = size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
        return new Distribution(size, median, sorted.get((int) Math.ceil(size * .95) - 1));
    }
    private static int count(List<Result> rows, Outcome outcome) { return (int) rows.stream().filter(row -> row.outcome() == outcome).count(); }
}
```

p95 для трёх наблюдений фактически отражает максимум малого набора. Это не надёжная оценка хвоста распределения. Повторы одного вопроса зависимы: не объявляйте 30 вопросов × 3 повтора 90 независимыми задачами. Для серьёзного статистического вывода нужен отдельный дизайн эксперимента.

## 17. Ручная оценка и Spring AI Evaluator

Прочитайте question, expectedAnswer, реальные sources и ответ. Дайте две оценки 0..2:

- Ответ: 0 — неверный/выдуманный, 1 — частичный, 2 — правильный и достаточный.
- Источники: 0 — выдуманные/неподтверждающие, 1 — частично подтверждают, 2 — подтверждают утверждения либо обоснованно отсутствуют при отказе.

Для UNKNOWN правильная реакция — сообщить о нехватке данных, а не угадать SLA или security policy. Отдельно можно отметить refusalCorrect. Поле reviewedAt проставляет сервер; ручная оценка во время RUNNING запрещена, чтобы не смешать её с обновлением прогресса.

Spring AI предлагает [Evaluator и RelevancyEvaluator](https://docs.spring.io/spring-ai/reference/api/testing.html). Они могут использовать другую модель для проверки ответа относительно контекста. Такая оценка полезна как дополнительный сигнал, но тоже может ошибаться и требует собственной проверки. В обязательной практике нет LLM-судьи: зелёный unit-тест и автоматическая релевантность не подменяют человека.

## 18. API экспериментов

| Запрос | Назначение |
| --- | --- |
| GET /api/evaluation/dataset?split=DEV | Версия, кейсы и рубрика для человека |
| POST /api/evaluation/runs | Старт, 202 и UUID |
| GET /api/evaluation/runs | Последние 20 сохранённых прогонов |
| GET /api/evaluation/runs/{id} | Run с raw results и сводкой |
| POST /api/evaluation/runs/{id}/cancel | Запрос остановки |
| PUT /api/evaluation/runs/{id}/judgments/{ordinal} | Ручная оценка строки |
| GET /api/evaluation/runs/{id}/report.md | Markdown для скачивания |

```bash
curl -sS http://127.0.0.1:8080/api/evaluation/runs \
  -H 'Content-Type: application/json' \
  -d '{"split":"DEV","caseIds":["dev-01","dev-02"],"modes":["CHAT","FULL_CONTEXT","RAG"],"repeats":1,"topK":4,"threshold":0.35,"warmup":true}'
```

Скопируйте реальный UUID из ответа. Далее `RUN_UUID` — место для подстановки:

```bash
curl -sS http://127.0.0.1:8080/api/evaluation/runs/RUN_UUID
curl -sS -X POST http://127.0.0.1:8080/api/evaluation/runs/RUN_UUID/cancel
curl -sS -X PUT http://127.0.0.1:8080/api/evaluation/runs/RUN_UUID/judgments/0 \
  -H 'Content-Type: application/json' \
  -d '{"answer":1,"citations":0,"refusalCorrect":null,"notes":"Указан неподтверждённый источник"}'
curl -sS http://127.0.0.1:8080/api/evaluation/runs/RUN_UUID/report.md -o comparison.md
```

Пустой caseIds означает весь выбранный split. API позволяет выбрать один/два режима для диагностики без RAG; UI запускает все три. Запрос HOLDOUT с dev-ID отклоняется до inference.

## 19. Интерфейс: прогресс, сырой ответ, ручная оценка

`static/js/evaluation.js` содержит API helper, renderer и mount. После 202 клиент опрашивает run раз в секунду до terminal state. При загрузке страницы восстанавливается последний отчёт, новый model request автоматически не запускается. Запоздалый restore не заменит только что созданный job.

Renderer показывает таблицу с completed/no-evidence/failed и judged, а ниже — раскрываемые строки с текстом ответа, метриками и source content. Все модельные и документные строки выводятся через textContent. Для отсутствующих измерений показывается «—», оценки по умолчанию не выбраны.

Переключение DEV/HOLDOUT очищает поле конкретных ID. Это предотвращает случайную отправку dev-01 в HOLDOUT. Кнопка запуска блокируется во время job; остановка сохраняет результат. Исходные JSON и отчёт доступны после перезапуска процесса.

Модуль уже полностью подключён в `main.js`; разметка и стили находятся в static/index.html и css/styles.css. В ДЗ renderer также предоставлен готовым; новая задача относится к парному сравнению сохранённых прогонов.

## 20. Проверяем контракты без настоящей LLM

```bash
./mvnw test
npm ci
npm test
./mvnw verify -Ppgvector-it
```

Проверено: **171 Java unit/HTTP, 13 pgvector integration и 96 JavaScript**. Все 280 сценариев проходят без skipped. Обычный test не требует Docker; профиль pgvector-it требует настоящий daemon и отдельный временный контейнер.

Новые тесты проверяют:

- 20/10 split, отсечение чужих IDs и валидность evidence.
- Каждую комбинацию вопрос × режим × повтор и ротацию порядка.
- Два TTFT, пробельную delta, null без модели, actual usage отдельно от оценки.
- Warm-up вне сводки, ошибки/NO_EVIDENCE вне latency и отсутствие выдуманных оценок.
- Атомарное хранение, interrupted run и ручную оценку с server timestamp.
- Один активный job, отмену, смену digest и сохранность raw answer.
- Настоящий Spring AI HTTP payload: один вопрос без истории, одинаковые options, отсутствие answer key.
- Все три pipeline с настоящим PostgreSQL, query embedding и QuestionAnswerAdvisor.
- UI literal text, блокировки, polling, ручные оценки, export и гонку restore.

Синтетический HTTP-стенд возвращает заранее заданный текст и vectors. Успешный тест доказывает контракт, а не семантическое качество qwen/EmbeddingGemma. Настоящие веса в техническом прогоне не загружались.

## 21. Проводим настоящий эксперимент

Заполните [Strategy-Decision.md](evaluation/Strategy-Decision.md). Начните с двух DEV-кейсов, проверьте, что source/case/config верны. Затем уберите IDs для всех 20, выберите 2–3 повтора. При трёх режимах это 120–180 measured requests, плюс warm-up. Для 10 HOLDOUT × 3 повтора — 90 запросов.

Не меняйте encoder и corpus между режимами. Стоимость ingestion и публикации измеряйте отдельно: они не входят в row totalMs. Для изменяемого корпуса эта стоимость и частота обновлений важны для решения, даже если per-query RAG быстрее.

При оценке сохраняйте неудачные случаи. Запишите и качество источников, и отказ: ложный отказ на answerable-вопросе — потеря полезности, уверенная догадка на UNKNOWN — риск ошибочного ответа.

Не сравнивайте разные прогоны только по средним: проверьте datasetVersion, корпус, модель/digest, pipeline, budget, warm-up, число попыток и количество ручных оценок. При неполных данных вывод должен быть предварительным.

## 22. Как выбрать стратегию

Для малого стабильного корпуса FULL_CONTEXT может быть проще: нет retrieval и риска не найти нужный фрагмент. Если корпус не помещается, часто меняется или содержит много нерелевантного текста, RAG даёт механизм отбора, но добавляет индекс и собственные ошибки. CHAT полезен для общих Java-вопросов, однако не получает сведения вымышленного проекта.

Это предположения для проверки, а не заранее объявленный победитель. Решение по вашему продукту должно ссылаться на обработанные кейсы, ошибки, ручные оценки, задержки и стоимость обновления данных. Скрипт специально не выбирает стратегию автоматически по минимальному TTFT.

## 23. Диагностика

| Симптом | Проверка |
| --- | --- |
| EXPERIMENT_BUSY | Предыдущий job активен; дождаться/остановить |
| MODEL_NOT_FOUND / MODEL_UNAVAILABLE | Локальный chat tag, digest и Ollama |
| INDEX_NOT_READY / STALE_INDEX | Ingestion, публикация, corpusVersion |
| RAG_DISABLED | repopilot.rag.enabled и выбранные modes |
| EXPERIMENT_CHANGED | Guard обнаружил несовместимые условия; нужен отдельный новый прогон |
| CONDITIONS_UNVERIFIED | Проверка после запроса не удалась; строка не считается успешной |
| WARMUP_FAILED | Измерения не начались, raw warm-up сохранён |
| CONTEXT_TOO_LARGE | Обязательный контекст не помещается; ошибка в отчёте, не silent truncation |
| EMBEDDING_UNAVAILABLE на длинном вопросе | Отдельное окно encoder и truncate=false; не путать с chat context |
| INTERRUPTED | Процесс завершился до финала, автоматического продолжения нет |
| Нет оценки качества | Не сохранён Judgment; «—» не равно 0 |
| Нет TTFT | Не было непустого текста модели или chat inference не вызывался |
| Provider tokens отличаются от estimated | Разные методы подсчёта; это ожидаемо |
| UI потерял обновление при сетевой ошибке | Перезагрузить страницу: сохранённый job восстановится; не запускать дубликат |

Не удаляйте индекс/историю/отчёты для ускорения диагностики. Ошибки определяют условия применимости стратегии.

## 24. Домашнее задание

В [homework](../homework/README.md) полностью готовы dataset, EvidenceCoverage, расписание прогонов, измерения, summary, renderer и панель этой лекции. Новая задача — парный отчёт регрессий между двумя сохранёнными runs: рост среднего не должен скрывать ухудшение конкретного ответа или цитаты.

Реализуйте только [PairedRunComparator.compare](../homework/src/main/java/dev/myfrei/repopilot/homework/PairedRunComparator.java). Сопоставляйте строки по caseId + repetition в выбранном режиме, проверяйте совместимость условий и работайте с объединением ключей. Явно показывайте непарные/неоценённые результаты и execution regressions. Любое ухудшение answer или citation не превращайте в победу из-за улучшения другой оси. Delta времени допустима только для двух COMPLETED; отчёт не вызывает модель и не меняет исходные runs.

Готовый `GET /api/evaluation/pairs?baseline=...&candidate=...&mode=RAG` читает runs из RunStore. [Шесть новых acceptance-проверок](../homework/src/test/java/dev/myfrei/repopilot/homework/PairedRegressionHomeworkAcceptanceTest.java) включают контрпример: средняя оценка выросла, но отдельный case регрессировал. По [VERIFICATION](../VERIFICATION.md), каркас собирается, базовые **171 быстрых Java + 96 JS** зелёные, **6 новых Java** сначала красные. Во временном эталоне с заменой только comparator проходят **177 быстрых Java + 13 pgvector IT + 96 JS**, тесты не менялись; IT выполнены с Docker.

Выполните `./mvnw test`, `npm test` и `./mvnw verify -Ppgvector-it` из homework. Создайте два сопоставимых DEV-run, внесите ручные оценки и заполните [SUBMISSION](../homework/SUBMISSION.md): какие строки улучшились/ухудшились и что осталось непарным. HOLDOUT не используйте для очередного выбора threshold. Настоящие выводы о качестве требуют реальных моделей и оценки ответов, fixtures доказывают только алгоритм отчёта.

Усложнение: добавьте парный bootstrap на уровне вопросов, не считая повторы одного вопроса независимыми задачами.

## 25. Вопросы для самопроверки

1. Почему CHAT с предыдущими ответами — плохой baseline без контекста?
2. Почему warm-up не доказывает cache hit и не создаёт измеренный CAG?
3. Что различают model TTFT и end-to-end TTFT?
4. Почему NO_EVIDENCE не должен получать TTFT=0?
5. Что означает средняя оценка 2/2 при judged=1 из 30?
6. Почему маленький p95 нельзя уверенно сравнивать между режимами?
7. Чем retrievedEvidenceHit отличается от providedEvidenceHit и faithfulness?
8. Почему после смены digest надо остановить, а не продолжить общий отчёт?
9. Какие расходы RAG не вошли в per-query latency?
10. Что произошло с HOLDOUT, если threshold выбрали по его ошибкам?

## 26. Источники

- [Spring AI Evaluation Testing](https://docs.spring.io/spring-ai/reference/api/testing.html) — интерфейс Evaluator и модельная оценка релевантности.
- [Spring AI Ollama Chat](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) — options и metadata.
- [Ollama Chat API](https://docs.ollama.com/api/chat) — streaming и provider counters.
- [CAG: исходная статья, arXiv 2412.15605](https://arxiv.org/abs/2412.15605) — постановка cache-augmented generation и условия эксперимента.
- [Java 25 System.nanoTime](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/System.html#nanoTime()) — измерение интервалов.
- [Spring AI RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html) и [PgVectorStore](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) — существующий путь 2.3.

Следующий урок 3.1 — разбор причин плохих ответов: данные, retrieval, бюджет, prompt, модель и проверяемые исправления.
