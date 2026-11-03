import test from "node:test";
import assert from "node:assert/strict";
import { renderRag, mountRag } from "../../main/resources/static/js/rag.js";
import { streamMessage } from "../../main/resources/static/js/stream-api.js";
import { createConversationApi } from "../../main/resources/static/js/conversation-api.js";
import { page, frame, streamResponse, deferred } from "./fixtures.mjs";
const source = { label: "S1", chunkId: "a".repeat(64), path: "<img src=x onerror=alert(1)>", title: "Title", startLine: 2, endLine: 4, score: .95 };
const report = { status: "READY", indexFingerprint: "b".repeat(64), sources: [source], estimatedInputTokens: 600,
  inputBudget: 3584, includedHistoryTurns: 1, omittedHistoryTurns: 2, omittedChunks: 1 };
const frames = rag => [frame("started"), frame("rag", { rag }), frame("delta", { text: "Ответ [S1]" }), frame("completed")];

test("RAG sources use literal text and snapshot-specific links", () => {
  const dom = page(); const document = dom.window.document; renderRag(document, report);
  assert.equal(document.querySelector("#context-sources img"), null);
  assert.match(document.getElementById("context-sources").textContent, /<img src=x/);
  assert.equal(document.querySelector("#context-sources a").getAttribute("href"), `/api/rag/chunks/${report.indexFingerprint}/${source.chunkId}`);
  assert.match(document.getElementById("context-summary").textContent, /не вероятность/);
  dom.window.close();
});
test("empty evidence clears previous source links", () => {
  const dom = page(); renderRag(dom.window.document, report); renderRag(dom.window.document, { ...report, status: "NO_EVIDENCE", sources: [] });
  assert.equal(dom.window.document.querySelectorAll("#context-sources li").length, 0);
  assert.match(dom.window.document.getElementById("context-summary").textContent, /Chat-модель не вызывалась/); dom.window.close();
});
test("parser accepts a single RAG report before deltas", async () => {
  const events = []; await streamMessage("Q", { onEvent: event => events.push(event.type) }, async () => streamResponse(frames(report)));
  assert.deepEqual(events, ["started", "rag", "delta", "completed"]);
});
test("parser rejects malformed scores, links and duplicate reports", async () => {
  for (const rag of [{ ...report, indexFingerprint: "../bad" }, { ...report, sources: [{ ...source, score: "1" }] }, { ...report, estimatedInputTokens: 9000 }]) {
    await assert.rejects(streamMessage("Q", {}, async () => streamResponse(frames(rag))), { code: "PROTOCOL_ERROR" });
  }
  await assert.rejects(streamMessage("Q", {}, async () => streamResponse([frame("started"), frame("rag", { rag: report }), frame("rag", { rag: report })])), { code: "PROTOCOL_ERROR" });
});
test("RAG requests use separate endpoint and omit legacy mode field", async () => {
  let request; const api = createConversationApi(async (url, options) => { request = { url, options }; return streamResponse(frames(report)); });
  await api.send("id", "Q", { mode: "RAG" });
  assert.equal(request.url, "/api/conversations/id/rag/stream"); assert.deepEqual(JSON.parse(request.options.body), { message: "Q" });
});
test("publish locks the button and reports manifest", async () => {
  const dom = page(); const wait = deferred(); let calls = 0;
  const view = mountRag(dom.window.document, async () => { calls++; await wait.promise; return Response.json({ reused: false, manifest: { chunkCount: 9, workspace: "taskshelf", fingerprint: "a".repeat(64) } }); });
  const first = view.publish(); assert.equal(await view.publish(), false); assert.equal(calls, 1);
  wait.resolve(); assert.equal(await first, true); assert.match(dom.window.document.getElementById("rag-status").textContent, /9 чанков/);
  assert.equal(dom.window.document.getElementById("publish-rag").disabled, false); dom.window.close();
});
test("publish shows public server errors and unlocks", async () => {
  const dom = page(); const view = mountRag(dom.window.document, async () => Response.json({ detail: "Сначала создайте снимок" }, { status: 409 }));
  assert.equal(await view.publish(), false); assert.equal(dom.window.document.getElementById("rag-error").textContent, "Сначала создайте снимок");
  assert.equal(dom.window.document.getElementById("publish-rag").disabled, false); dom.window.close();
});
