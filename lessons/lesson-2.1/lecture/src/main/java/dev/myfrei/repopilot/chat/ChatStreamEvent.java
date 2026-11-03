package dev.myfrei.repopilot.chat;

public record ChatStreamEvent(String type, String text, String code, String message,
        dev.myfrei.repopilot.context.ContextReport context) {

    public ChatStreamEvent(String type, String text, String code, String message) {
        this(type, text, code, message, null);
    }

    public static ChatStreamEvent context(dev.myfrei.repopilot.context.ContextReport report) {
        return new ChatStreamEvent("context", "", "", "", report);
    }

    public static ChatStreamEvent started() {
        return new ChatStreamEvent("started", "", "", "");
    }

    public static ChatStreamEvent delta(String text) {
        return new ChatStreamEvent("delta", text, "", "");
    }

    public static ChatStreamEvent completed() {
        return new ChatStreamEvent("completed", "", "", "");
    }

    public static ChatStreamEvent failed(String code, String message) {
        return new ChatStreamEvent("failed", "", code, message);
    }

    public boolean terminal() {
        return this.type.equals("completed") || this.type.equals("failed");
    }
}
