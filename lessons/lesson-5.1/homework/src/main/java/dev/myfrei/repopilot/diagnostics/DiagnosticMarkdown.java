package dev.myfrei.repopilot.diagnostics;

public final class DiagnosticMarkdown {
    private DiagnosticMarkdown() { }
    public static String render(DiagnosticReport report) {
        var out = new StringBuilder("# Разбор причин ответа RepoPilot\n\n");
        out.append("Run: ").append(report.runId()).append("; origin: ").append(report.origin()).append("; rules: ").append(report.rulesVersion()).append(".\n\n")
                .append(report.note()).append("\n\nСовместимость baseline: ").append(report.baselineCompatible()).append(".\n\n");
        for (var row : report.rows()) {
            out.append("## ").append(row.ordinal()).append(" · ").append(row.caseId()).append(" · ").append(row.mode()).append("\n\n")
                    .append("Категория: ").append(row.finding().category()).append(" — ").append(row.finding().label()).append(".\n\nНаблюдаемые факты:\n\n");
            for (var fact : row.finding().facts()) out.append("- ").append(fact).append('\n');
            out.append("\nГипотеза: ").append(row.finding().hypothesis()).append("\n\nПроверочный эксперимент: ").append(row.finding().nextExperiment())
                    .append("\n\nTrace: ").append(row.traceAvailable() ? row.traceId() : "недоступен: " + row.traceWarning())
                    .append("\n\nПодтверждённый вывод студента: **заполнить после эксперимента**.\n\n");
        }
        return out.toString();
    }
}
