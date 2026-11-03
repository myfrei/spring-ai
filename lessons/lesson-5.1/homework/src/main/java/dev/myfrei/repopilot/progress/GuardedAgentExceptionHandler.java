package dev.myfrei.repopilot.progress;
import dev.myfrei.repopilot.agent.*;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=GuardedAgentController.class)
public class GuardedAgentExceptionHandler {
 private final AgentExceptionHandler baseline;
 public GuardedAgentExceptionHandler(AgentExceptionHandler baseline){this.baseline=baseline;}
 @ExceptionHandler(AgentProblem.class)public ProblemDetail problem(AgentProblem error){return baseline.problem(error);}
 @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})public ProblemDetail invalid(Exception error){return baseline.invalid(error);}
}
