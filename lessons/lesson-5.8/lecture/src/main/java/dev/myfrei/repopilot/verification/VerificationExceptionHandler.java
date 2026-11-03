package dev.myfrei.repopilot.verification;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.plan.*;
import dev.myfrei.repopilot.skills.*;
import dev.myfrei.repopilot.workspace.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = VerificationController.class)
public class VerificationExceptionHandler {
  @ExceptionHandler(VerificationProblem.class)
  public ProblemDetail verification(VerificationProblem e) {
    var status =
        e.code().contains("INVALID")
            ? HttpStatus.BAD_REQUEST
            : e.code().endsWith("NOT_FOUND")
                ? HttpStatus.NOT_FOUND
                : e.code().equals("VERIFICATION_CAPACITY")
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : e.code().equals("VERIFICATION_COMMAND_DENIED")
                            || e.code().equals("VERIFICATION_SCOPE_DENIED")
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

  @ExceptionHandler(PlanException.class)
  public ProblemDetail plan(PlanException e) {
    return new PlanExceptionHandler().plan(e);
  }

  @ExceptionHandler(SkillException.class)
  public ProblemDetail skill(SkillException e) {
    return new SkillExceptionHandler().skill(e);
  }

  @ExceptionHandler(WorkspaceException.class)
  public ProblemDetail workspace(WorkspaceException e) {
    return verification(new VerificationProblem(e.code()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception e) {
    var d =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Проверьте закрытый JSON запроса.");
    d.setProperty("code", "VERIFICATION_INVALID_REQUEST");
    return d;
  }
}
