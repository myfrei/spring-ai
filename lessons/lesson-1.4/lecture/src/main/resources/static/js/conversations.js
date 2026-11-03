import { mountChat } from "./app.js";
import { isBusy } from "./chat-controller.js";
import { createConversationApi } from "./conversation-api.js";

export function historyMessages(turns) {
  const statuses = { completed: "complete", pending: "pending", failed: "interrupted",
    interrupted: "interrupted", cancelled: "cancelled" };
  return turns.flatMap(turn => [
    { role: "user", content: turn.question },
    { role: "assistant", content: turn.answer, status: statuses[turn.status] || "interrupted" }
  ]);
}

export function mountConversations(document, { api = createConversationApi(), storage = globalThis.localStorage } = {}) {
  let selected = null;
  let loading = false;
  let conversations = [];
  const list = document.getElementById("conversation-list");
  const notice = document.getElementById("conversation-notice");
  const chat = mountChat(document, (message, options) => {
    if (!selected) throw new Error("Сначала создайте диалог.");
    return api.send(selected, message, options);
  }, syncControls);
  const busy = () => loading || isBusy(chat.getState().phase);
  function savedId() { try { return storage?.getItem("repopilot.conversationId"); } catch { return null; } }
  function syncControls(state) {
    const disabled = loading || isBusy(state.phase);
    document.getElementById("new-conversation").disabled = disabled;
    document.getElementById("refresh-conversations").disabled = disabled;
    for (const button of list.querySelectorAll("button")) button.disabled = disabled;
    document.getElementById("message").disabled = disabled || !selected;
    document.getElementById("send-button").disabled = disabled || !selected;
  }
  function lock(value) { loading = value; syncControls(chat.getState()); }
  function renderList() {
    list.replaceChildren();
    for (const conversation of conversations) {
      const item = document.createElement("li");
      const button = document.createElement("button");
      button.type = "button"; button.textContent = conversation.title;
      button.setAttribute("aria-current", String(conversation.id === selected));
      button.addEventListener("click", () => select(conversation.id));
      item.append(button); list.append(item);
    }
  }
  async function show(id) {
    const detail = await api.detail(id);
    chat.load(historyMessages(detail.turns));
    selected = id;
    document.getElementById("conversation-title").textContent = detail.conversation.title;
    try { storage?.setItem("repopilot.conversationId", id); } catch { /* History lives on the server. */ }
    document.getElementById("message").value = "";
    document.getElementById("character-count").textContent = "0 / 4000";
    renderList();
    notice.textContent = "История загружена. Прерванные ответы не входят в память модели.";
  }
  async function select(id) {
    if (busy()) return false;
    lock(true);
    try { await show(id); return true; }
    catch (error) { notice.textContent = error.message; return false; }
    finally { lock(false); }
  }
  async function refresh() {
    if (busy()) return false;
    lock(true);
    try {
      conversations = await api.list();
      renderList();
      const preferred = selected || savedId();
      const id = conversations.find(item => item.id === preferred)?.id || conversations[0]?.id;
      if (id) await show(id);
      else {
        selected = null;
        chat.load([]);
        document.getElementById("conversation-title").textContent = "Выберите диалог";
        notice.textContent = "Создайте первый диалог, чтобы начать разговор.";
      }
      return true;
    } catch (error) { notice.textContent = error.message; return false; }
    finally { lock(false); }
  }
  async function create() {
    if (busy()) return false;
    lock(true);
    try {
      const created = await api.create(`Диалог ${conversations.length + 1}`);
      conversations = [created, ...conversations];
      await show(created.id);
      return true;
    } catch (error) { notice.textContent = error.message; return false; }
    finally { lock(false); }
  }
  document.getElementById("new-conversation").addEventListener("click", create);
  document.getElementById("refresh-conversations").addEventListener("click", refresh);
  const ready = refresh();
  return { ready, select, create, refresh, chat, selectedId: () => selected };
}
