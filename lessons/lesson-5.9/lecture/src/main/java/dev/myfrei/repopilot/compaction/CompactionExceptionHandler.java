package dev.myfrei.repopilot.compaction;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.plan.*;
import dev.myfrei.repopilot.skills.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = CompactionController.class)
public class CompactionExceptionHandler {
  @ExceptionHandler(CompactionProblem.class)
  public ProblemDetail context(CompactionProblem error) {
    var detail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Отчёт проекции не найден.");
    detail.setProperty("code", error.code());
    return detail;
  }

  @ExceptionHandler(PlanException.class)
  public ProblemDetail plan(PlanException error) {
    return new PlanExceptionHandler().plan(error);
  }

  @ExceptionHandler(SkillException.class)
  public ProblemDetail skill(SkillException error) {
    return new SkillExceptionHandler().skill(error);
  }

  @ExceptionHandler(AgentProblem.class)
  public ProblemDetail agent(AgentProblem error) {
    return new AgentExceptionHandler().problem(error);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception ignored) {
    var detail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Проверьте закрытый JSON плана и maxInputTokens1..32768.");
    detail.setProperty("code", "COMPACTION_INVALID_REQUEST");
    return detail;
  }
}
