package dev.myfrei.repopilot.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LlmUnavailableException.class)
    public ProblemDetail handleLlmUnavailable(LlmUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("LLM unavailable");
        return problem;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class,
            HttpMessageNotReadableException.class})
    public ProblemDetail handleInvalidRequest(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Проверьте поля запроса: message — 1–4000 символов, title — 1–80 символов.");
        problem.setTitle("Invalid request");
        return problem;
    }
}
