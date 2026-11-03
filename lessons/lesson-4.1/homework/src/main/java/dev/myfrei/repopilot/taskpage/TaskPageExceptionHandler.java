package dev.myfrei.repopilot.taskpage;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=TaskPageController.class)
public class TaskPageExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
    public ProblemDetail invalid(Exception ignored){var detail=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Проверьте status, limit 1–5 и cursor для текущего снимка.");detail.setProperty("code","INVALID_TASK_PAGE");return detail;}
}
