import { streamMessage } from "./stream-api.js";
import { createChatController } from "./chat-controller.js";
import { createChatView } from "./chat-view.js";

export function mountChat(document, send = streamMessage, onStateChange = () => {}) {
  const view = createChatView(document);
  const controller = createChatController({ send, onStateChange(state) {
    view.render(state);
    onStateChange(state);
  } });
  const input = document.getElementById("message");
  const count = document.getElementById("character-count");
  function updateCount() {
    count.textContent = `${input.value.length} / 4000`;
  }
  input.addEventListener("input", updateCount);
  document.getElementById("chat-form").addEventListener("submit", async event => {
    event.preventDefault();
    const success = await controller.submit(input.value);
    if (success) {
      input.value = "";
      updateCount();
    }
    if (!input.disabled) input.focus();
  });
  document.getElementById("cancel-button").addEventListener("click", () => {
    controller.cancel();
    if (!input.disabled) input.focus();
  });
  document.getElementById("retry-button").addEventListener("click", async () => {
    const repeated = controller.getState().retryMessage;
    const success = await controller.retry();
    if (success && input.value.trim() === repeated) {
      input.value = "";
      updateCount();
    }
    if (!input.disabled) input.focus();
  });
  updateCount();
  return controller;
}
