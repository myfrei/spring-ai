package dev.myfrei.repopilot.chat;

public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException() {
        super("Локальная LLM не вернула ответ. Проверьте Ollama, модель и время ожидания.");
    }

    public LlmUnavailableException(Throwable cause) {
        super("Локальная LLM не вернула ответ. Проверьте Ollama, модель и время ожидания.", cause);
    }
}
