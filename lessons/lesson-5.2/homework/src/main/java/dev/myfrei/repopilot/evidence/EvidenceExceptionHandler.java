package dev.myfrei.repopilot.evidence;
import dev.myfrei.repopilot.agent.*;
import dev.myfrei.repopilot.workspace.WorkspaceException;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=EvidenceController.class)
public class EvidenceExceptionHandler{
 private final AgentExceptionHandler agent;
 public EvidenceExceptionHandler(AgentExceptionHandler agent){this.agent=agent;}
 @ExceptionHandler(AgentProblem.class)public ProblemDetail agent(AgentProblem error){return agent.problem(error);}
 @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})public ProblemDetail invalid(Exception ignored){return problem(HttpStatus.BAD_REQUEST,"EVIDENCE_INVALID_REQUEST","Передайте только1–12 claims с относительным путём, SHA-256 и целыми границами строк.");}
 @ExceptionHandler(UnsupportedOperationException.class)public ProblemDetail todo(UnsupportedOperationException ignored){return problem(HttpStatus.NOT_IMPLEMENTED,"EVIDENCE_NOT_IMPLEMENTED","Реализуйте EvidencePolicy.check.");}
 @ExceptionHandler(WorkspaceException.class)public ProblemDetail unavailable(WorkspaceException error){return problem(HttpStatus.CONFLICT,error.code(),"Метаданные прочитанных файлов этого run недоступны. Выполните чтение в новом run.");}
 @ExceptionHandler(EvidenceProblem.class)public ProblemDetail active(EvidenceProblem error){return problem(HttpStatus.CONFLICT,error.code(),"Дождитесь завершения run.");}
 private static ProblemDetail problem(HttpStatus status,String code,String message){var detail=ProblemDetail.forStatusAndDetail(status,message);detail.setProperty("code",code);return detail;}
}
