package dev.myfrei.repopilot.researchplan;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.research.ResearchException;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ResearchReadPlanController.class)
public class ResearchReadPlanExceptionHandler {
  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception ignored) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "READ_PLAN_INVALID_REQUEST",
        "Передайте только целый readLineBudget от 1 до 160.");
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ProblemDetail todo(UnsupportedOperationException ignored) {
    return problem(
        HttpStatus.NOT_IMPLEMENTED,
        "READ_PLAN_NOT_IMPLEMENTED",
        "Реализуйте ResearchCheckPlanPolicy.check.");
  }

  @ExceptionHandler(ResearchReadPlanProblem.class)
  public ProblemDetail boundary(ResearchReadPlanProblem error) {
    return problem(
        HttpStatus.CONFLICT,
        error.code(),
        "Серверные доказательства превышают границы учебного read-plan; частичный результат не"
            + " выдан.");
  }

  @ExceptionHandler(ResearchException.class)
  public ProblemDetail research(ResearchException error) {
    return problem(
        error.code().equals("RESEARCH_RUN_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
        error.code(),
        "Исследовательский отчёт недоступен.");
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
