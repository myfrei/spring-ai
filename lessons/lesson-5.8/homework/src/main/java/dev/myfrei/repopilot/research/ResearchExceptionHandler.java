package dev.myfrei.repopilot.research;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.plan.*;
import dev.myfrei.repopilot.skills.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = ResearchController.class)
public class ResearchExceptionHandler {
  @ExceptionHandler(ResearchException.class)
  public ProblemDetail research(ResearchException error) {
    HttpStatus status =
        error.code().endsWith("NOT_FOUND")
            ? HttpStatus.NOT_FOUND
            : error.code().equals("RESEARCH_CAPACITY")
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.CONFLICT;
    var detail =
        ProblemDetail.forStatusAndDetail(
            status, "Подзапуск недоступен; проверьте владельца run, состояние и бюджет.");
    detail.setProperty("code", error.code());
    return detail;
  }

  @ExceptionHandler(AgentProblem.class)
  public ProblemDetail agent(AgentProblem error) {
    return new AgentExceptionHandler().problem(error);
  }

  @ExceptionHandler(PlanException.class)
  public ProblemDetail plan(PlanException error) {
    return new PlanExceptionHandler().plan(error);
  }

  @ExceptionHandler(SkillException.class)
  public ProblemDetail skill(SkillException error) {
    return new SkillExceptionHandler().skill(error);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception ignored) {
    var detail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Проверьте закрытый research JSON и допустимые лимиты.");
    detail.setProperty("code", "RESEARCH_INVALID_REQUEST");
    return detail;
  }
}
