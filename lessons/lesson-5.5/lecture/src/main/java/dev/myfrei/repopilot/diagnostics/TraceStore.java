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
