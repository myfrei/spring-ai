package dev.myfrei.repopilot.evaluation;

import dev.myfrei.repopilot.conversation.ConversationProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = EvaluationController.class)
public class EvaluationExceptionHandler {
    @ExceptionHandler(ConversationProblem.class) public ProblemDetail problem(ConversationProblem error) {
        var status = error.code().equals("NOT_FOUND") ? HttpStatus.NOT_FOUND : error.code().startsWith("REPORT_") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT;
        var detail = ProblemDetail.forStatusAndDetail(status, error.getMessage()); detail.setProperty("code", error.code()); return detail;
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalid(Exception error) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Проверьте split/caseIds/modes, repeats 1–3, topK 1–8, threshold 0–1 и оценки 0–2."); }
}
