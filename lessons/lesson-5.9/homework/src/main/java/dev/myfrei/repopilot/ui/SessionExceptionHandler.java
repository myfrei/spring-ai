package dev.myfrei.repopilot.ui;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.plan.*;
import dev.myfrei.repopilot.research.*;
import dev.myfrei.repopilot.skills.*;
import dev.myfrei.repopilot.verification.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = SessionController.class)
public class SessionExceptionHandler {
  @ExceptionHandler(SessionProblem.class)
  public ProblemDetail session(SessionProblem e) {
    var status =
        e.code().equals("SESSION_NOT_FOUND")
            ? HttpStatus.NOT_FOUND
            : e.code().contains("CAPACITY")
                ? HttpStatus.SERVICE_UNAVAILABLE
                : e.code().equals("SESSION_APPROVAL_SCOPE")
                    ? HttpStatus.FORBIDDEN
                    : HttpStatus.CONFLICT;
    var d = ProblemDetail.forStatusAndDetail(status, e.getMessage());
    d.setProperty("code", e.code());
    return d;
  }

  @ExceptionHandler(AgentProblem.class)
  public ProblemDetail agent(AgentProblem e) {
    return new AgentExceptionHandler().problem(e);
  }

  @ExceptionHandler(VerificationProblem.class)
  public ProblemDetail verification(VerificationProblem e) {
    return new VerificationExceptionHandler().verification(e);
  }

  @ExceptionHandler(ControlledProblem.class)
  public Object controlled(ControlledProblem e) {
    return new ControlledExceptionHandler().controlled(e);
  }

  @ExceptionHandler(ResearchException.class)
  public ProblemDetail research(ResearchException e) {
    return new ResearchExceptionHandler().research(e);
  }

  @ExceptionHandler(PlanException.class)
  public ProblemDetail plan(PlanException e) {
    return new PlanExceptionHandler().plan(e);
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
            HttpStatus.BAD_REQUEST, "Проверьте запрос, курсор и идентификаторы.");
    d.setProperty("code", "SESSION_INVALID_REQUEST");
    return d;
  }
}
