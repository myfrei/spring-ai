import test from "node:test";
import assert from "node:assert/strict";
import { renderContext, clearContext } from "../../main/resources/static/js/context-view.js";
import { mountConversations } from "../../main/resources/static/js/conversations.js";
import { streamMessage } from "../../main/resources/static/js/stream-api.js";
import { createConversationApi } from "../../main/resources/static/js/conversation-api.js";
import { page, deferred, frame, streamResponse, enter, submit, tick } from "./fixtures.mjs";

const report = (changes = {}) => ({ mode: "FULL_CONTEXT", corpusVersion: "a".repeat(64),
  estimatedInputTokens: 700, inputBudget: 3584, includedHistoryTurns: 1, omittedHistoryTurns: 2,
  cacheEvidence: "NOT_MEASURED", sources: [{ id: "overview", title: "TaskShelf <img src=x>", path: "README.md", sha256: "b".repeat(64) }], ...changes });
const wire = value => frame("context", { context: value });
const conversation = { id: "a", title: "Java" };
function ui(t, send) {
  const dom = page(); t.after(() => dom.window.close());
  const controller = mountConversations(dom.window.document, { storage: undefined, api: {
    list: async () => [conversation], detail: async () => ({ conversation, turns: [] }), create: async () => conversation, send
  } });
  return { dom, document: dom.window.document, controller };
}

test("report describes estimated budget and attached documents without claiming citations or cache", t => {
  const dom = page(); t.after(() => dom.window.close()); renderContext(dom.window.document, report());
  const text = dom.window.document.getElementById("context-summary").textContent;
  assert.match(text, /700 \/ 3584/); assert.match(text, /Кеширование не измерялось/);
  assert.match(text, /не означают подтверждённые цитаты/);
  assert.equal(dom.window.document.querySelector("#context-sources a").getAttribute("href"), "/api/corpus/documents/overview");
});
test("source titles remain literal text and clearing removes previous references", t => {
  const dom = page(); t.after(() => dom.window.close()); const doc = dom.window.document;
  renderContext(doc, report()); assert.equal(doc.querySelectorAll("#context-sources img").length, 0);
  assert.match(doc.querySelector("#context-sources a").textContent, /<img src=x>/);
  clearContext(doc); assert.equal(doc.querySelectorAll("#context-sources a").length, 0);
});
test("CHAT report replaces old full-context source list", t => {
  const dom = page(); t.after(() => dom.window.close()); renderContext(dom.window.document, report());
  renderContext(dom.window.document, report({ mode: "CHAT", corpusVersion: "", sources: [] }));
  assert.equal(dom.window.document.querySelectorAll("#context-sources a").length, 0);
  assert.match(dom.window.document.getElementById("context-summary").textContent, /Без документов/);
});
test("selected mode reaches API and actual context report renders before answer completion", async t => {
  const pending = deferred(); let received;
  const f = ui(t, (id, message, options) => { received = options; return pending.promise; });
  await f.controller.ready; f.document.getElementById("context-mode").value = "FULL_CONTEXT";
  enter(f.dom, "Вопрос"); submit(f.dom);
  assert.equal(received.mode, "FULL_CONTEXT"); assert.equal(f.document.getElementById("context-mode").disabled, true);
  received.onEvent({ type: "context", context: report() });
  assert.match(f.document.getElementById("context-summary").textContent, /FULL_CONTEXT/);
  received.onEvent({ type: "delta", text: "Ответ" }); pending.resolve(); await tick();
  assert.equal(f.document.getElementById("context-mode").disabled, false);
});
test("switching conversation clears report that belongs to previous attempt", async t => {
  const f = ui(t, async () => {}); await f.controller.ready;
  renderContext(f.document, report()); await f.controller.refresh();
  assert.equal(f.document.querySelectorAll("#context-sources a").length, 0);
});
test("stream protocol delivers one context report between started and delta", async () => {
  const events = [];
  await streamMessage("Q", { onEvent: event => events.push(event.type) }, async () =>
    streamResponse([frame("started"), wire(report()), frame("delta", { text: "A" }), frame("completed")]));
  assert.deepEqual(events, ["started", "context", "delta", "completed"]);
});
test("duplicate context reports fail protocol", async () => {
  await assert.rejects(streamMessage("Q", {}, async () => streamResponse([frame("started"), wire(report()), wire(report())])),
    error => error.code === "PROTOCOL_ERROR");
});
test("context report after a delta fails protocol", async () => {
  await assert.rejects(streamMessage("Q", {}, async () => streamResponse([frame("started"), frame("delta", { text: "A" }), wire(report())])),
    error => error.code === "PROTOCOL_ERROR");
});
test("invalid report budget or unsafe source ID fails protocol", async () => {
  for (const invalid of [report({ estimatedInputTokens: 4000 }), report({ sources: [{ id: "../private", title: "Bad" }] })]) {
    await assert.rejects(streamMessage("Q", {}, async () => streamResponse([frame("started"), wire(invalid)])),
      error => error.code === "PROTOCOL_ERROR");
  }
});
test("conversation API serializes mode and message in context stream request", async () => {
  let request;
  const api = createConversationApi(async (path, options) => { request = { path, options };
    return streamResponse([frame("started"), wire(report()), frame("delta", { text: "A" }), frame("completed")]); });
  await api.send("a", "Q", { mode: "FULL_CONTEXT" });
  assert.equal(request.path, "/api/conversations/a/context/stream");
  assert.deepEqual(JSON.parse(request.options.body), { message: "Q", mode: "FULL_CONTEXT" });
});
