package dev.myfrei.repopilot.selection;

import dev.myfrei.repopilot.skills.SkillException;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = SelectionController.class)
public class SelectionExceptionHandler {
  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  public ProblemDetail invalid(Exception ignored) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "SELECTION_INVALID_REQUEST",
        "Передайте от1до12 name/version и целый budgetBytes от1до4096.");
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ProblemDetail todo(UnsupportedOperationException ignored) {
    return problem(
        HttpStatus.NOT_IMPLEMENTED,
        "SELECTION_NOT_IMPLEMENTED",
        "Реализуйте SkillSelectionPolicy.check.");
  }

  @ExceptionHandler(SkillException.class)
  public ProblemDetail catalog(SkillException error) {
    return problem(
        HttpStatus.CONFLICT, error.code(), "Каталог Skills недоступен или нарушает ограничения.");
  }

  private static ProblemDetail problem(HttpStatus status, String code, String message) {
    var d = ProblemDetail.forStatusAndDetail(status, message);
    d.setProperty("code", code);
    return d;
  }
}
