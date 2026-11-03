package dev.myfrei.repopilot.batch;

import dev.myfrei.repopilot.controlled.ControlledProblem;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BatchController.class)
public class BatchExceptionHandler {
  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  public ProblemDetail invalid(Exception ignored) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "BATCH_INVALID_REQUEST",
        "Передайте только proposalIds: от1до12 канонических UUID.");
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ProblemDetail todo(UnsupportedOperationException ignored) {
    return problem(
        HttpStatus.NOT_IMPLEMENTED,
        "BATCH_NOT_IMPLEMENTED",
        "Реализуйте ProposalBatchPolicy.check.");
  }

  @ExceptionHandler(ControlledProblem.class)
  public ProblemDetail proposal(ControlledProblem error) {
    return problem(HttpStatus.NOT_FOUND, error.code(), "Одно из предложений недоступно.");
  }

  private static ProblemDetail problem(HttpStatus status, String code, String message) {
    var d = ProblemDetail.forStatusAndDetail(status, message);
    d.setProperty("code", code);
    return d;
  }
}
