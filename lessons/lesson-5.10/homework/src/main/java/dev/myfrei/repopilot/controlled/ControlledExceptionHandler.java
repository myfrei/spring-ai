package dev.myfrei.repopilot.controlled;

import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.WorkspaceException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = ControlledController.class)
public final class ControlledExceptionHandler {
  @ExceptionHandler(ControlledProblem.class)
  public ResponseEntity<Map<String, String>> controlled(ControlledProblem problem) {
    String code = problem.code();
    int status =
        code.equals("PROPOSAL_NOT_FOUND")
            ? 404
            : code.equals("PROPOSAL_CAPACITY")
                ? 503
                : code.startsWith("INVALID_") ? 400 : code.equals("MODE_DENIED") ? 403 : 409;
    return ResponseEntity.status(status)
        .body(Map.of("code", code, "message", problem.getMessage()));
  }

  @ExceptionHandler(WorkspaceException.class)
  public ResponseEntity<Map<String, String>> workspace(WorkspaceException problem) {
    return controlled(new ControlledProblem(problem.code()));
  }

  @ExceptionHandler(AgentProblem.class)
  public ProblemDetail agent(AgentProblem problem) {
    return new AgentExceptionHandler().problem(problem);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<Map<String, String>> invalid(Exception problem) {
    return controlled(new ControlledProblem("INVALID_REQUEST"));
  }
}
