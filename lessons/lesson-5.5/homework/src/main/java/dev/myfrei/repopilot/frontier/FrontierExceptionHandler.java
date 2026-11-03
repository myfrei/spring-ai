package dev.myfrei.repopilot.frontier;

import dev.myfrei.repopilot.plan.PlanException;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = FrontierController.class)
public class FrontierExceptionHandler {
  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception ignored) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "FRONTIER_INVALID_REQUEST",
        "Передайте maxSteps от 1 до 4 и readLineBudget от 1 до 160.");
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ProblemDetail todo(UnsupportedOperationException ignored) {
    return problem(
        HttpStatus.NOT_IMPLEMENTED,
        "FRONTIER_NOT_IMPLEMENTED",
        "Реализуйте PlanFrontierPolicy.check.");
  }

  @ExceptionHandler(PlanException.class)
  public ProblemDetail plan(PlanException error) {
    return problem(
        error.code().equals("PLAN_RUN_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
        error.code(),
        "План недоступен для проверки.");
  }

  private static ProblemDetail problem(HttpStatus status, String code, String message) {
    var d = ProblemDetail.forStatusAndDetail(status, message);
    d.setProperty("code", code);
    return d;
  }
}
