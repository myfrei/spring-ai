package dev.myfrei.repopilot.evaluation;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

public class RunStore {
    private final Path directory;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public RunStore(String directory) { this.directory = Path.of(directory).toAbsolutePath(); }
    public synchronized void save(Experiment run) {
        Path temporary = null;
        try {
            Files.createDirectories(this.directory);
            byte[] bytes = this.mapper.writeValueAsBytes(run);
            if (bytes.length > 16_777_216) throw new IOException("Run too large");
            temporary = Files.createTempFile(this.directory, "run-", ".tmp"); Files.write(temporary, bytes);
            Files.move(temporary, path(run.id()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw new EvaluationProblem("REPORT_WRITE_FAILED", "Не удалось атомарно сохранить отчёт. Проверьте каталог evaluation."); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    public synchronized Experiment load(UUID id) {
        if (!Files.exists(path(id))) throw new EvaluationProblem("NOT_FOUND", "Эксперимент не найден.");
        try {
            if (Files.size(path(id)) > 16_777_216) throw new IOException("Run too large");
            var run = this.mapper.readValue(Files.readAllBytes(path(id)), Experiment.class);
            if (!run.id().equals(id)) throw new IOException("Identity mismatch");
            return run;
        } catch (IOException | RuntimeException error) { throw new EvaluationProblem("REPORT_CORRUPT", "Сохранённый отчёт не прошёл чтение/проверку."); }
    }
    public synchronized List<Experiment> list() {
        if (!Files.isDirectory(this.directory)) return List.of();
        try (var files = Files.list(this.directory)) {
            return files.filter(path -> path.getFileName().toString().matches("run-[0-9a-f-]{36}\\.json"))
                    .map(path -> load(UUID.fromString(path.getFileName().toString().substring(4, 40))))
                    .sorted(Comparator.comparing(Experiment::startedAt).reversed()).toList();
        } catch (IOException error) { throw new EvaluationProblem("REPORT_READ_FAILED", "Не удалось прочитать каталог отчётов."); }
    }
    public synchronized void recoverInterrupted() {
        for (var run : list()) if (run.state() == Experiment.State.RUNNING) save(new Experiment(run.id(), run.startedAt(),
                java.time.Instant.now().toString(), Experiment.State.INTERRUPTED, run.request(), run.conditions(), run.planned(),
                run.warmups(), run.results(), run.judgments(), "Процесс завершился до финального статуса. Автоматического повтора нет."));
    }
    private Path path(UUID id) { return this.directory.resolve("run-" + id + ".json"); }
}
