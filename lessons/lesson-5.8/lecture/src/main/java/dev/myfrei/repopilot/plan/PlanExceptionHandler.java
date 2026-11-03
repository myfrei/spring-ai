package dev.myfrei.repopilot.plan;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.skills.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = PlanController.class)
public class PlanExceptionHandler {
  @ExceptionHandler(PlanException.class)
  public ProblemDetail plan(PlanException e) {
    var status =
        e.code().equals("PLAN_RUN_NOT_FOUND")
            ? HttpStatus.NOT_FOUND
            : e.code().equals("PLAN_CAPACITY")
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.CONFLICT;
    var d =
        ProblemDetail.forStatusAndDetail(
            status, "План недоступен; проверьте run и состояние шагов.");
    d.setProperty("code", e.code());
    return d;
  }

  @ExceptionHandler(AgentProblem.class)
  public ProblemDetail agent(AgentProblem e) {
    return new AgentExceptionHandler().problem(e);
  }

  @ExceptionHandler(SkillException.class)
  public ProblemDetail skill(SkillException e) {
    return new SkillExceptionHandler().skill(e);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception e) {
    var d =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Проверьте закрытый JSON question/mode/skills/steps.");
    d.setProperty("code", "PLAN_INVALID_REQUEST");
    return d;
  }
}
