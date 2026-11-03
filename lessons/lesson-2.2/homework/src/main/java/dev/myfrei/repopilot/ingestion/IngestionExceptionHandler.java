package dev.myfrei.repopilot.ingestion;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {IngestionController.class, dev.myfrei.repopilot.homework.IncrementalIndexController.class})
public class IngestionExceptionHandler {
    @ExceptionHandler(IngestionProblem.class)
    public ProblemDetail handle(IngestionProblem problem) {
        var status = switch (problem.code()) {
            case "INDEX_BUSY", "MODEL_CHANGED" -> HttpStatus.CONFLICT;
            case "MODEL_NOT_FOUND", "EMBEDDING_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "INVALID_VECTOR" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        var detail = ProblemDetail.forStatusAndDetail(status, problem.getMessage());
        detail.setTitle("Ingestion failed"); detail.setProperty("code", problem.code()); return detail;
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalid(Exception error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Проверьте strategy, maxCodePoints (64–2000) и overlapCodePoints (0..size-1); максимум 512 чанков.");
    }
}
