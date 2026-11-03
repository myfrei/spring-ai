export function createChatView(document) {
  const messages = document.getElementById("messages");
  const input = document.getElementById("message");
  const sendButton = document.getElementById("send-button");
  const status = document.getElementById("status");
  const labels = { idle: "Готов к вопросу", submitting: "Готовлю ответ…",
    success: "Ответ получен", error: "Не удалось отправить вопрос" };

  function render(state) {
    const busy = state.phase === "submitting";
    input.disabled = busy;
    sendButton.disabled = busy;
    document.getElementById("chat-form").setAttribute("aria-busy", String(busy));
    status.textContent = labels[state.phase];
    document.getElementById("empty-state").hidden = state.messages.length > 0;
    document.getElementById("error-box").hidden = !state.error;
    document.getElementById("error-message").textContent = state.error;

    messages.replaceChildren();
    for (const message of state.messages) {
      const item = document.createElement("li");
      item.className = `message message-${message.role}`;
      const role = document.createElement("span");
      role.className = "message-role";
      role.textContent = message.role === "user" ? "Вы" : "RepoPilot";
      const body = document.createElement("pre");
      body.textContent = message.content;
      item.append(role, body);
      messages.append(item);
    }
    messages.scrollTop = messages.scrollHeight;
  }
  return { render };
}
