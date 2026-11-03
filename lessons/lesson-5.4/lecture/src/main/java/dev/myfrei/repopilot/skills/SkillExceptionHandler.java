package dev.myfrei.repopilot.skills;

import dev.myfrei.repopilot.agent.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = SkillController.class)
public class SkillExceptionHandler {
  @ExceptionHandler(SkillException.class)
  public ProblemDetail skill(SkillException error) {
    HttpStatus status =
        error.code().equals("SKILL_NOT_FOUND") || error.code().equals("SKILL_RUN_NOT_FOUND")
            ? HttpStatus.NOT_FOUND
            : HttpStatus.CONFLICT;
    var detail =
        ProblemDetail.forStatusAndDetail(
            status, "Навык не загружен; проверьте каталог и его версию.");
    detail.setProperty("code", error.code());
    return detail;
  }

  @ExceptionHandler(AgentProblem.class)
  public ProblemDetail agent(AgentProblem error) {
    return new AgentExceptionHandler().problem(error);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception ignored) {
    var detail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Проверьте question, mode и список skills.");
    detail.setProperty("code", "SKILL_INVALID_REQUEST");
    return detail;
  }
}
