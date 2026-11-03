package dev.myfrei.repopilot.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ConversationExceptionHandler {
    @ExceptionHandler(ConversationProblem.class)
    public ProblemDetail handle(ConversationProblem problem) {
        var status = problem.code().equals("NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        var detail = ProblemDetail.forStatusAndDetail(status, problem.getMessage());
        detail.setTitle("Conversation error"); detail.setProperty("code", problem.code());
        return detail;
    }
}
