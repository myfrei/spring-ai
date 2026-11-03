package dev.myfrei.repopilot.chat;

public record ChatStreamEvent(String type, String text, String code, String message) {

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
