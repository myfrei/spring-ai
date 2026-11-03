import { sendMessage } from "./chat-api.js";
import { createChatController } from "./chat-controller.js";
import { createChatView } from "./chat-view.js";
import { createDraftStore } from "./draft-store.js";
export function mountDraftChat(document, send = sendMessage, storage) {
  // Some browsers deny access to the property itself, before getItem can be called.
  if (storage === undefined) {
    try { storage = globalThis.localStorage; }
    catch { storage = null; }
  }
  const store = createDraftStore(storage); const view = createChatView(document);
  const controller = createChatController({ send, onStateChange: view.render });
  const input = document.getElementById("message"); const status = document.getElementById("draft-status");
  const count = () => { document.getElementById("character-count").textContent = `${input.value.length} / 4000`; };
  const save = () => { try { status.textContent = store.save(input.value) ? "Черновик сохранён в браузере." : "Черновик не сохранён; чат продолжает работать."; } catch { status.textContent = "Реализуйте сохранение черновика."; } };
  try { input.value = store.read(); } catch { status.textContent = "Реализуйте восстановление черновика."; }
  input.addEventListener("input", () => { count(); save(); });
  document.getElementById("clear-draft").addEventListener("click", () => { if (!input.disabled) { input.value = ""; count(); save(); } });
  document.getElementById("chat-form").addEventListener("submit", async event => {
    event.preventDefault(); const submitted = input.value;
    const success = await controller.submit(submitted);
    if (success && input.value === submitted) {
      input.value = "";
      try { store.clearIfUnchanged(submitted); } catch { status.textContent = "Реализуйте очистку отправленного черновика."; }
    }
    count(); if (!input.disabled) input.focus();
  });
  count(); return controller;
}
