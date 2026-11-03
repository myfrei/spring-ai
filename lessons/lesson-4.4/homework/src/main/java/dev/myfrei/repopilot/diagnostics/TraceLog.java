package dev.myfrei.repopilot.diagnostics;

import java.util.LinkedHashMap;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
public final class TraceLog {
    private TraceLog() { }
    public static String line(UUID traceId, DiagnosticTrace.Context context, DiagnosticTrace.Event event) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("traceId", traceId); fields.put("runId", context.runId()); fields.put("caseId", context.caseId());
        fields.put("ordinal", context.ordinal()); fields.put("mode", context.mode()); fields.put("synthetic", context.synthetic());
        fields.put("event", event);
        return JsonMapper.builder().build().writeValueAsString(fields);
    }
}
