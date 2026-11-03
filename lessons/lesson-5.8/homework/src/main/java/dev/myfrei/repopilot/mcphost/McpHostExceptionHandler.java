package dev.myfrei.repopilot.mcphost;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=McpHostController.class)
public class McpHostExceptionHandler {
    @ExceptionHandler(McpHostProblem.class)public ProblemDetail host(McpHostProblem error){
        HttpStatus status;
        if(error.gatewayKind()!=null)status=error.gatewayKind()==GatewayException.Kind.AVAILABILITY?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.BAD_GATEWAY;
        else status=switch(error.code()){
            case "HOST_UNKNOWN_TASK","HOST_TOOL_ARGUMENTS"->HttpStatus.BAD_REQUEST;
            case "HOST_CONTEXT_TOO_LARGE","CONTEXT_TOO_LARGE"->HttpStatus.valueOf(413);
            case "HOST_MODEL_UNAVAILABLE","HOST_IDENTITY_UNAVAILABLE"->HttpStatus.SERVICE_UNAVAILABLE;
            default->HttpStatus.CONFLICT;
        };
        var detail=ProblemDetail.forStatusAndDetail(status,error.getMessage());detail.setProperty("code",error.code());detail.setProperty("report",error.report());
        if(error.gatewayKind()!=null)detail.setProperty("gatewayKind",error.gatewayKind());return detail;
    }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})public ProblemDetail invalid(Exception ignored){
        var detail=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Проверьте известный taskId и question 1–1000.");detail.setProperty("code","HOST_INVALID_REQUEST");return detail;
    }
}
