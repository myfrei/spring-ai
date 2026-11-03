export function validateMessage(message) {
  if (typeof message !== "string" || !message.trim()) {
    return "Введите вопрос перед отправкой.";
  }
  if (message.length > 4000) {
    return "Вопрос должен быть не длиннее 4000 символов.";
  }
  return "";
}

export function isBusy(phase) { return phase === "connecting" || phase === "streaming"; }

export function createChatController({ send, onStateChange = () => {} }) {
  let state = { phase: "idle", messages: [], error: "", retryMessage: null };
  let active = null;
  let serial = 0;

  function getState() { return { ...state, messages: state.messages.map(message => ({ ...message })) }; }
  function update(changes) { state = { ...state, ...changes }; onStateChange(getState()); }
  function changeMessage(index, changes) {
    return state.messages.map((message, i) => i === index ? { ...message, ...changes } : message);
  }

  async function run(message, appendUser) {
    const messages = appendUser ? [...state.messages, { role: "user", content: message }] : [...state.messages];
    const attempt = { id: ++serial, message, index: messages.length, abort: new AbortController() };
    active = attempt;
    const current = () => active?.id === attempt.id && !attempt.abort.signal.aborted;
    messages.push({ role: "assistant", content: "", status: "pending" });
    update({ phase: "connecting", messages, error: "", retryMessage: null });
    try {
      await send(message, { signal: attempt.abort.signal, onEvent(event) {
        if (!current()) return;
        if (event.type === "delta") {
          update({ phase: "streaming", messages: changeMessage(attempt.index,
            { content: state.messages[attempt.index].content + event.text, status: "streaming" }) });
        }
      } });
      if (!current()) return false;
      if (!state.messages[attempt.index].content.trim()) throw new Error("Модель вернула пустой ответ.");
      update({ phase: "success", error: "", retryMessage: null,
        messages: changeMessage(attempt.index, { status: "complete" }) });
      return true;
    } catch (error) {
      if (!current()) return false;
      update({ phase: "error", error: error.message || "Ответ не завершён.", retryMessage: message,
        messages: changeMessage(attempt.index, { status: "interrupted" }) });
      return false;
    } finally {
      if (active?.id === attempt.id) active = null;
    }
  }

  async function submit(rawMessage) {
    if (isBusy(state.phase)) return false;
    const error = validateMessage(rawMessage);
    if (error) { update({ phase: "error", error, retryMessage: null }); return false; }
    return run(rawMessage.trim(), true);
  }

  function cancel() {
    if (!active || !isBusy(state.phase)) return false;
    const attempt = active;
    active = null;
    attempt.abort.abort();
    update({ phase: "cancelled", error: "", retryMessage: attempt.message,
      messages: changeMessage(attempt.index, { status: "cancelled" }) });
    return true;
  }

  async function retry() {
    if (!["error", "cancelled"].includes(state.phase) || !state.retryMessage) return false;
    return run(state.retryMessage, true);
  }

  function load(messages) {
    if (isBusy(state.phase)) return false;
    update({ phase: "idle", messages: messages.map(message => ({ ...message })), error: "", retryMessage: null });
    return true;
  }

  onStateChange(getState());
  return { getState, submit, cancel, retry, load };
}
