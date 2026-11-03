package dev.myfrei.repopilot.capstone;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.ui.*;
import dev.myfrei.repopilot.verification.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = CapstoneController.class)
public class CapstoneExceptionHandler {
  @ExceptionHandler(CapstoneProblem.class)
  public ProblemDetail capstone(CapstoneProblem error) {
    var status =
        error.code().endsWith("NOT_FOUND")
            ? HttpStatus.NOT_FOUND
            : error.code().equals("CAPSTONE_MCP_UNAVAILABLE")
                ? HttpStatus.SERVICE_UNAVAILABLE
                : error.code().equals("CAPSTONE_TASK_ID")
                    ? HttpStatus.BAD_REQUEST
                    : HttpStatus.CONFLICT;
    var result =
        ProblemDetail.forStatusAndDetail(
            status,
            "Финальный сценарий не завершён; проверьте реальные свидетельства и фиксированный"
                + " workspace.");
    result.setProperty("code", error.code());
    return result;
  }

  @ExceptionHandler(SessionProblem.class)
  public ProblemDetail session(SessionProblem error) {
    return new SessionExceptionHandler().session(error);
  }

  @ExceptionHandler(AgentProblem.class)
  public ProblemDetail agent(AgentProblem error) {
    return new AgentExceptionHandler().problem(error);
  }

  @ExceptionHandler(ControlledProblem.class)
  public Object controlled(ControlledProblem error) {
    return new ControlledExceptionHandler().controlled(error);
  }

  @ExceptionHandler(VerificationProblem.class)
  public ProblemDetail verification(VerificationProblem error) {
    return new VerificationExceptionHandler().verification(error);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception error) {
    var result =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Передайте только документированные taskId или UUID полей стадии.");
    result.setProperty("code", "CAPSTONE_INVALID_REQUEST");
    return result;
  }
}
