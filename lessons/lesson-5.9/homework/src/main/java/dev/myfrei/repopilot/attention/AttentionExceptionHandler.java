package dev.myfrei.repopilot.attention;

import dev.myfrei.repopilot.agent.*;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AttentionController.class)
public class AttentionExceptionHandler {
  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception ignored) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "ATTENTION_INVALID_REQUEST",
        "Передайте maxItems от 1 до 12 и подтверждённые позиции событий известных сессий.");
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ProblemDetail todo(UnsupportedOperationException ignored) {
    return problem(
        HttpStatus.NOT_IMPLEMENTED,
        "ATTENTION_NOT_IMPLEMENTED",
        "Реализуйте AttentionQueuePolicy.check.");
  }

  @ExceptionHandler(AgentProblem.class)
  public ProblemDetail agent(AgentProblem error) {
    return new AgentExceptionHandler().problem(error);
  }

  @ExceptionHandler(AttentionProblem.class)
  public ProblemDetail boundary(AttentionProblem error) {
    return problem(
        HttpStatus.CONFLICT,
        error.code(),
        "Серверная область сессий не совпала с выбранным диалогом.");
  }

  private static ProblemDetail problem(HttpStatus status, String code, String message) {
    var d = ProblemDetail.forStatusAndDetail(status, message);
    d.setProperty("code", code);
    return d;
  }
}
