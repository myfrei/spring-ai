package dev.myfrei.repopilot.rankingeval;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=RankingEvaluationController.class)
public class RankingEvaluationExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalid(Exception ignored) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Проверьте metadata: до 100 document IDs, grades 0..3, K 1..20.");
        detail.setProperty("code", "INVALID_RANKING_OBSERVATION");
        return detail;
    }
}
