package dev.myfrei.repopilot.agent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=AgentController.class)
public class AgentExceptionHandler {
 @ExceptionHandler(AgentProblem.class)public ProblemDetail problem(AgentProblem error){var status=switch(error.code()){case "AGENT_BUSY"->HttpStatus.CONFLICT;case "AGENT_CAPACITY"->HttpStatus.SERVICE_UNAVAILABLE;default->HttpStatus.NOT_FOUND;};var detail=ProblemDetail.forStatusAndDetail(status,error.getMessage());detail.setProperty("code",error.code());return detail;}
 @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})public ProblemDetail invalid(Exception ignored){var detail=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Проверьте UUID и question1–1000 символов.");detail.setProperty("code","AGENT_INVALID_REQUEST");return detail;}
}
