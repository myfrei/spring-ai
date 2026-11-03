package dev.myfrei.repopilot.tools;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=ToolsController.class)
public class ToolsExceptionHandler {
    @ExceptionHandler(ToolsProblem.class) public ProblemDetail tools(ToolsProblem error) {
        var status=switch (error.code()) {
            case "TOOLS_UNKNOWN_TASK","TOOLS_ARGUMENTS_INVALID","TOOLS_WRONG_TASK"->HttpStatus.BAD_REQUEST;
            case "TOOLS_CONTEXT_TOO_LARGE"->HttpStatus.PAYLOAD_TOO_LARGE;
            case "TOOLS_MODEL_UNAVAILABLE","TOOLS_IDENTITY_UNAVAILABLE"->HttpStatus.SERVICE_UNAVAILABLE;
            default->HttpStatus.CONFLICT;
        };
        var detail=ProblemDetail.forStatusAndDetail(status,error.getMessage());detail.setProperty("code",error.code());
        if (error.report()!=null) detail.setProperty("report",error.report());return detail;
    }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class}) public ProblemDetail invalid(Exception ignored) {
        var detail=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Проверьте taskId из каталога и question длиной 1–1000.");detail.setProperty("code","TOOLS_INVALID_REQUEST");return detail;
    }
}
