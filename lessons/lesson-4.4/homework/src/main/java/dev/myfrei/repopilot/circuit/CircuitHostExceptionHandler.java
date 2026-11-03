package dev.myfrei.repopilot.circuit;

import dev.myfrei.repopilot.mcphost.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** Reuses the lecture's error representation for the opt-in homework endpoint. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=CircuitHostController.class)
public class CircuitHostExceptionHandler {
    private final McpHostExceptionHandler baseline;
    public CircuitHostExceptionHandler(McpHostExceptionHandler baseline){this.baseline=baseline;}
    @ExceptionHandler(McpHostProblem.class) public ProblemDetail host(McpHostProblem error){return baseline.host(error);}
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class}) public ProblemDetail invalid(Exception error){return baseline.invalid(error);}
}
