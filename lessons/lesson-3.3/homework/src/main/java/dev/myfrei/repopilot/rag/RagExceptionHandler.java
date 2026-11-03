package dev.myfrei.repopilot.rag;

import dev.myfrei.repopilot.ingestion.IngestionProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {RagController.class, dev.myfrei.repopilot.advisor.AdvisorController.class, dev.myfrei.repopilot.query.QueryController.class})
public class RagExceptionHandler {
    @ExceptionHandler(IngestionProblem.class) public ProblemDetail ingestion(IngestionProblem problem) {
        return detail(problem.code(), problem.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
    }
    @ExceptionHandler(RagProblem.class) public ProblemDetail rag(RagProblem problem) {
        var status = switch (problem.code()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "RAG_DATABASE_UNAVAILABLE", "EMBEDDING_UNAVAILABLE", "MODEL_NOT_FOUND", "QUERY_MODEL_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "CONTEXT_TOO_LARGE" -> HttpStatus.valueOf(413);
            default -> HttpStatus.CONFLICT;
        };
        return detail(problem.code(), problem.getMessage(), status);
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalid(Exception ignored) { return detail("INVALID_REQUEST", "Проверьте вопрос, topK (1–8), threshold (0–1) и идентификаторы источника.", HttpStatus.BAD_REQUEST); }
    private ProblemDetail detail(String code, String message, HttpStatus status) {
        var detail = ProblemDetail.forStatusAndDetail(status, message); detail.setProperty("code", code); return detail;
    }
}
