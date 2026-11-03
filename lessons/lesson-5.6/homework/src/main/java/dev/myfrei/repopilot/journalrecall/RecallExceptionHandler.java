package dev.myfrei.repopilot.journalrecall;

import dev.myfrei.repopilot.agent.*;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = RecallController.class)
public class RecallExceptionHandler {
  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception ignored) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "RECALL_INVALID_REQUEST",
        "Передайте от 1 до 8 callIds и целый payloadBudgetBytes от 1 до 4096.");
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ProblemDetail todo(UnsupportedOperationException ignored) {
    return problem(
        HttpStatus.NOT_IMPLEMENTED, "RECALL_NOT_IMPLEMENTED", "Реализуйте ToolRecallPolicy.check.");
  }

  @ExceptionHandler(AgentProblem.class)
  public ProblemDetail agent(AgentProblem error) {
    return new AgentExceptionHandler().problem(error);
  }

  private static ProblemDetail problem(HttpStatus status, String code, String message) {
    var d = ProblemDetail.forStatusAndDetail(status, message);
    d.setProperty("code", code);
    return d;
  }
}
