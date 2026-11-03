package dev.myfrei.repopilot.evaluation;

import java.util.Locale;
import dev.myfrei.repopilot.evaluation.Experiment.*;
import tools.jackson.databind.json.JsonMapper;
public final class MarkdownReport {
    private MarkdownReport() { }
    public static String render(Experiment run) {
        var text = new StringBuilder("# Сравнение CHAT / FULL_CONTEXT / RAG\n\n");
        text.append("Run: ").append(run.id()).append("; состояние: ").append(run.state()).append(".\n\n")
                .append("Запланировано: ").append(run.planned()).append("; обработано: ").append(run.results().size())
                .append("; warm-up отдельно: ").append(run.warmups().size()).append(".\n\n");
        text.append("## Условия\n\n").append(fence(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(run.conditions())));
        text.append(fence("Request: " + JsonMapper.builder().build().writeValueAsString(run.request()) + "\nStarted: " + run.startedAt() + "\nFinished: " + run.finishedAt() + "\n" + run.message()));
        text.append("\n## Сводка\n\nЗадержки относятся только к COMPLETED с вызовом LLM. NO_EVIDENCE и ошибки считаются отдельно. TTFT — первый непустой текст, наблюдаемый Java-сервисом; доставка в браузер не включена.\n\n")
                .append("| Режим | Попыток | Completed | No evidence | Failed | Cancelled | Оценено | Answer /2 | Citations /2 | Total median ms | TTFT e2e median ms | TTFT n |\n| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (var group : ComparisonSummary.summarize(run)) text.append("| ").append(group.mode()).append(" | ").append(group.attempted()).append(" | ")
                .append(group.completed()).append(" | ").append(group.noEvidence()).append(" | ").append(group.failed()).append(" | ").append(group.cancelled()).append(" | ")
                .append(group.judged()).append(" | ").append(number(group.meanAnswerScore())).append(" | ").append(number(group.meanCitationScore())).append(" | ")
                .append(number(group.totalMs().median())).append(" | ").append(number(group.endToEndTtftMs().median())).append(" | ").append(group.endToEndTtftMs().n()).append(" |\n");
        text.append("\nПолные распределения (n, median, nearest-rank p95), оценка входа и evidence coverage:\n\n")
                .append(fence(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(ComparisonSummary.summarize(run))));
        text.append("\n## Исходные результаты\n\n");
        for (var row : run.results()) {
            text.append("### ").append(row.ordinal()).append(" · ").append(row.caseId()).append(" · ").append(row.mode()).append(" · повтор ").append(row.repetition()).append("\n\n");
            text.append(fence(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(row)));
            var judgment = run.judgments().get(row.ordinal());
            text.append(judgment == null ? "Ручная оценка: **не выполнена**.\n\n" : fence("Ручная оценка: " + JsonMapper.builder().build().writeValueAsString(judgment)));
        }
        text.append("## Warm-up\n\nИсключён из сводки. Прогрев не доказывает cold/warm KV-cache. Cache evidence: NOT_MEASURED.\n\n")
                .append(fence(JsonMapper.builder().build().writeValueAsString(run.warmups())));
        text.append("\n## Вывод студента\n\n- Выбранная стратегия и основания:\n- Ошибочные/неоценённые случаи:\n- Что осталось гипотезой:\n- Условия пересмотра решения:\n");
        return text.toString();
    }
    private static String number(Double value) { return value == null ? "—" : String.format(Locale.ROOT, "%.2f", value); }
    private static String fence(String value) {
        var matcher = java.util.regex.Pattern.compile("`+").matcher(value); int longest = 2;
        while (matcher.find()) longest = Math.max(longest, matcher.group().length());
        String fence = "`".repeat(longest + 1); return fence + "text\n" + value + "\n" + fence + "\n\n";
    }
}
