package dev.myfrei.repopilot.conversation;

public class ConversationProblem extends RuntimeException {
    private final String code;

    public ConversationProblem(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return this.code; }
}
