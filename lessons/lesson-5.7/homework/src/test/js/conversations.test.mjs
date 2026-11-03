import test from "node:test";
import assert from "node:assert/strict";
import { mountConversations, historyMessages } from "../../main/resources/static/js/conversations.js";
import { createConversationApi } from "../../main/resources/static/js/conversation-api.js";
import { page, deferred, enter, submit, tick, completeSend, frame, streamResponse } from "./fixtures.mjs";

const a = { id: "a", title: "Java" }, b = { id: "b", title: "Spring" };
const turn = (question, answer, status = "completed") => ({ question, answer, status });
function fixture(t, overrides = {}, storage = undefined) {
  const dom = page(); t.after(() => dom.window.close());
  const api = { list: async () => [a, b], detail: async id => ({ conversation: id === "a" ? a : b,
    turns: [turn(id === "a" ? "Вопрос A" : "Вопрос B", "Ответ " + id)] }),
    create: async () => ({ id: "c", title: "Диалог 3" }), send: async (id, message, options) => completeSend(message, options), ...overrides };
  const controller = mountConversations(dom.window.document, { api, storage });
  return { dom, document: dom.window.document, controller, api };
}

test("history maps all statuses and keeps partial text", () => {
  const messages = historyMessages([turn("Q1", "A1"), turn("Q2", "Часть", "failed"), turn("Q3", "Ещё", "cancelled"),
    turn("Q4", "После сбоя", "interrupted")]);
  assert.deepEqual(messages.filter(m => m.role === "assistant").map(m => m.status),
    ["complete", "interrupted", "cancelled", "interrupted"]);
  assert.equal(messages[3].content, "Часть");
});
test("empty server list leaves composer disabled until explicit create", async t => {
  let creates = 0;
  const f = fixture(t, { list: async () => [], create: async () => { creates++; return a; } });
  await f.controller.ready; assert.equal(creates, 0); assert.equal(f.document.getElementById("message").disabled, true);
  assert.equal(await f.controller.create(), true); assert.equal(creates, 1);
  assert.equal(f.controller.selectedId(), "a"); assert.equal(f.document.getElementById("message").disabled, false);
});
test("restores selected ID from storage and renders its own history", async t => {
  const values = new Map([["repopilot.conversationId", "b"]]);
  const f = fixture(t, {}, { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) });
  await f.controller.ready;
  assert.equal(f.controller.selectedId(), "b"); assert.equal(f.document.getElementById("conversation-title").textContent, "Spring");
  assert.match(f.document.getElementById("messages").textContent, /Вопрос B/);
  assert.doesNotMatch(f.document.getElementById("messages").textContent, /Вопрос A/);
});
test("unknown stored ID falls back to an existing conversation", async t => {
  const f = fixture(t, {}, { getItem: () => "deleted", setItem() {} });
  await f.controller.ready; assert.equal(f.controller.selectedId(), "a");
});
test("switch replaces old messages and clears input draft", async t => {
  const f = fixture(t); await f.controller.ready; enter(f.dom, "Черновик A");
  await f.controller.select("b");
  assert.deepEqual(f.controller.chat.getState().messages.map(m => m.content), ["Вопрос B", "Ответ b"]);
  assert.equal(f.document.getElementById("message").value, "");
});
test("loading blocks submit and competing switches", async t => {
  const wait = deferred(); const f = fixture(t, { list: () => wait.promise });
  assert.equal(f.document.getElementById("send-button").disabled, true);
  assert.equal(await f.controller.select("b"), false);
  wait.resolve([a]); await f.controller.ready;
});
test("active stream is scoped to selected ID and blocks switches/new conversations", async t => {
  const pending = deferred(); let received;
  const f = fixture(t, { send: (id, message, options) => { received = { id, message, options }; return pending.promise; } });
  await f.controller.ready; enter(f.dom, "Новый вопрос"); submit(f.dom);
  assert.equal(received.id, "a"); assert.equal(received.message, "Новый вопрос");
  assert.equal(await f.controller.select("b"), false); assert.equal(await f.controller.create(), false);
  received.options.onEvent({ type: "delta", text: "Ответ" }); pending.resolve(); await tick();
  assert.equal(await f.controller.select("b"), true);
});
test("load failure keeps current conversation and exposes recovery", async t => {
  const f = fixture(t, { detail: async id => { if (id === "b") throw new Error("Нет связи");
    return { conversation: a, turns: [turn("A", "Ответ")] }; } });
  await f.controller.ready; assert.equal(await f.controller.select("b"), false);
  assert.equal(f.controller.selectedId(), "a"); assert.equal(f.document.getElementById("message").disabled, false);
  assert.equal(f.document.getElementById("conversation-notice").textContent, "Нет связи");
});
test("initial server failure keeps composer unavailable and refresh recovers", async t => {
  let calls = 0; const f = fixture(t, { list: async () => { if (!calls++) throw new Error("SQLite недоступна"); return [a]; } });
  assert.equal(await f.controller.ready, false); assert.equal(f.document.getElementById("message").disabled, true);
  assert.equal(await f.controller.refresh(), true); assert.equal(f.document.getElementById("message").disabled, false);
});
test("blocked localStorage does not prevent server history restore", async t => {
  const f = fixture(t, {}, { getItem() { throw new Error("Denied"); }, setItem() { throw new Error("Denied"); } });
  assert.equal(await f.controller.ready, true); assert.equal(f.controller.selectedId(), "a");
});
test("conversation titles are rendered as literal text", async t => {
  const evil = { id: "x", title: '<img src=x onerror="alert(1)">' };
  const f = fixture(t, { list: async () => [evil], detail: async () => ({ conversation: evil, turns: [] }) });
  await f.controller.ready;
  assert.equal(f.document.querySelectorAll("#conversation-list img, #conversation-title img").length, 0);
  assert.equal(f.document.querySelector("#conversation-list button").textContent, evil.title);
});
test("refresh restores server partial status rather than inferring completion", async t => {
  const f = fixture(t, { detail: async () => ({ conversation: a, turns: [turn("Q", "Часть", "interrupted")] }) });
  await f.controller.ready; assert.equal(f.controller.chat.getState().messages[1].status, "interrupted");
  assert.equal(f.document.querySelector(".message-status").textContent, "Прерван");
});
test("API creates JSON conversation and encodes route ID", async () => {
  const calls = [];
  const api = createConversationApi(async (path, options) => { calls.push({ path, options });
    return new Response(JSON.stringify({ id: "test" }), { status: path === "/api/conversations" ? 201 : 200 }); });
  await api.create("Java"); await api.detail("a/b");
  assert.equal(calls[0].options.method, "POST"); assert.deepEqual(JSON.parse(calls[0].options.body), { title: "Java" });
  assert.equal(calls[1].path, "/api/conversations/a%2Fb");
});
test("API streaming uses conversation route and original AbortSignal", async () => {
  const abort = new AbortController(); let request;
  const api = createConversationApi(async (path, options) => { request = { path, options };
    return streamResponse([frame("started"), frame("delta", { text: "Ответ" }), frame("completed")]); });
  await api.send("conv-id", "Вопрос", { signal: abort.signal });
  assert.equal(request.path, "/api/conversations/conv-id/context/stream");
  assert.equal(request.options.signal, abort.signal);
});
test("API exposes safe 404 detail instead of accepting invalid history", async () => {
  const api = createConversationApi(async () => new Response(JSON.stringify({ detail: "Диалог не найден." }), { status: 404 }));
  await assert.rejects(api.detail("missing"), /Диалог не найден/);
});
