import { isBusy } from "./chat-controller.js";

export function createChatView(document) {
  const messages = document.getElementById("messages");
  const input = document.getElementById("message");
  const sendButton = document.getElementById("send-button");
  const status = document.getElementById("status");
  const labels = { idle: "Готов к вопросу", connecting: "Ожидаю начало ответа…", streaming: "Пишу ответ…",
    success: "Ответ завершён", error: "Ответ не завершён", cancelled: "Остановлено" };
  const messageLabels = { pending: "Ожидание", streaming: "Пишет…", complete: "Завершён",
    interrupted: "Прерван", cancelled: "Остановлен" };

  function render(state) {
    const busy = isBusy(state.phase);
    input.disabled = busy;
    sendButton.disabled = busy;
    document.getElementById("chat-form").setAttribute("aria-busy", String(busy));
    status.textContent = labels[state.phase];
    document.getElementById("empty-state").hidden = state.messages.length > 0;
    const canRetry = ["error", "cancelled"].includes(state.phase) && Boolean(state.retryMessage);
    document.getElementById("cancel-button").hidden = !busy;
    document.getElementById("retry-button").hidden = !canRetry;
    document.getElementById("retry-button").disabled = busy;
    document.getElementById("error-box").hidden = !(state.error || canRetry);
    document.getElementById("error-message").textContent = state.error
      || (state.phase === "cancelled" ? "Генерация остановлена. Вы можете повторить запрос." : "");

    messages.replaceChildren();
    for (const message of state.messages) {
      const item = document.createElement("li");
      item.className = `message message-${message.role}`;
      if (message.status) item.classList.add(`message-${message.status}`);
      const role = document.createElement("span");
      role.className = "message-role";
      role.textContent = message.role === "user" ? "Вы" : "RepoPilot";
      if (message.status) {
        const badge = document.createElement("span");
        badge.className = "message-status";
        badge.textContent = messageLabels[message.status];
        role.append(badge);
      }
      const body = document.createElement("pre");
      body.textContent = message.content;
      item.append(role, body);
      messages.append(item);
    }
    messages.scrollTop = messages.scrollHeight;
  }
  return { render };
}
