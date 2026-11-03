import { sendMessage } from "./chat-api.js";
import { createChatController } from "./chat-controller.js";
import { createChatView } from "./chat-view.js";

export function mountChat(document, send = sendMessage) {
  const view = createChatView(document);
  const controller = createChatController({ send, onStateChange: view.render });
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
  updateCount();
  return controller;
}
