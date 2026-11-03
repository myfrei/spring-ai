export function validateMessage(message) {
  if (typeof message !== "string" || !message.trim()) {
    return "Введите вопрос перед отправкой.";
  }
  if (message.length > 4000) {
    return "Вопрос должен быть не длиннее 4000 символов.";
  }
  return "";
}

export function createChatController({ send, onStateChange = () => {} }) {
  let state = { phase: "idle", messages: [], error: "" };

  function getState() {
    return { ...state, messages: state.messages.map(message => ({ ...message })) };
  }

  function update(changes) {
    state = { ...state, ...changes };
    onStateChange(getState());
  }

  async function submit(rawMessage) {
    if (state.phase === "submitting") return false;
    const error = validateMessage(rawMessage);
    if (error) {
      update({ phase: "error", error });
      return false;
    }
    const message = rawMessage.trim();
    update({ phase: "submitting", error: "",
      messages: [...state.messages, { role: "user", content: message }] });
    let answer;
    try {
      answer = await send(message);
    } catch (failure) {
      update({ phase: "error", error: failure.message || "Не удалось получить ответ." });
      return false;
    }
    update({ phase: "success", error: "",
      messages: [...state.messages, { role: "assistant", content: answer }] });
    return true;
  }

  onStateChange(getState());
  return { getState, submit };
}
