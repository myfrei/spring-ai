package dev.myfrei.repopilot.auditexport;

import dev.myfrei.repopilot.capstone.*;
import dev.myfrei.repopilot.controlled.*;
import dev.myfrei.repopilot.verification.*;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AuditExportController.class)
public class AuditExportExceptionHandler {
  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ProblemDetail invalid(Exception ignored) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "AUDIT_INVALID_REQUEST",
        "Передайте только целый maxBytes от 1 до 16384.");
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ProblemDetail todo(UnsupportedOperationException ignored) {
    return problem(
        HttpStatus.NOT_IMPLEMENTED,
        "AUDIT_NOT_IMPLEMENTED",
        "Реализуйте PortableAuditPolicy.export.");
  }

  @ExceptionHandler(AuditExportProblem.class)
  public ProblemDetail audit(AuditExportProblem error) {
    return problem(
        HttpStatus.CONFLICT,
        error.code(),
        "Полный безопасный пакет не сформирован; проверьте обязательные факты и бюджет.");
  }

  @ExceptionHandler(CapstoneProblem.class)
  public ProblemDetail capstone(CapstoneProblem error) {
    return new CapstoneExceptionHandler().capstone(error);
  }

  @ExceptionHandler(VerificationProblem.class)
  public ProblemDetail verification(VerificationProblem error) {
    return new VerificationExceptionHandler().verification(error);
  }

  @ExceptionHandler(ControlledProblem.class)
  public Object controlled(ControlledProblem error) {
    return new ControlledExceptionHandler().controlled(error);
  }

  private static ProblemDetail problem(HttpStatus status, String code, String message) {
    var d = ProblemDetail.forStatusAndDetail(status, message);
    d.setProperty("code", code);
    return d;
  }
}
