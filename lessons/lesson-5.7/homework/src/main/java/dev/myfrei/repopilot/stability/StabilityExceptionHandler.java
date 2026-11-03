package dev.myfrei.repopilot.stability;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.verification.*;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = StabilityController.class)
public class StabilityExceptionHandler {
  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception ignored) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "STABILITY_INVALID_REQUEST",
        "Передайте только reportIds: от 1 до 8 канонических UUID.");
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ProblemDetail todo(UnsupportedOperationException ignored) {
    return problem(
        HttpStatus.NOT_IMPLEMENTED,
        "STABILITY_NOT_IMPLEMENTED",
        "Реализуйте VerificationStabilityPolicy.check.");
  }

  @ExceptionHandler(VerificationProblem.class)
  public ProblemDetail verification(VerificationProblem error) {
    return new VerificationExceptionHandler().verification(error);
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
