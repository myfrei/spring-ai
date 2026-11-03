import test from "node:test";
import assert from "node:assert/strict";
import { createEvaluationApi, mountEvaluation, renderComparison } from "../../main/resources/static/js/evaluation.js";
import { page, deferred } from "./fixtures.mjs";
const id = "123e4567-e89b-12d3-a456-426614174000";
function payload(state = "COMPLETED") {
  const result = { ordinal: 0, caseId: "dev-01", mode: "CHAT", repetition: 1, question: "<img src=x onerror=alert(1)>", outcome: "COMPLETED",
    answer: "<script>answer</script>", code: "", timing: { totalMs: 50, retrievalMs: 0, modelTtftMs: 10 }, estimatedInputTokens: 42, providerInputTokens: null,
    providedEvidenceHit: false, sources: [{ label: "overview", path: "README.md", text: "<svg onload=alert(1)>" }] };
  return { run: { id, state, results: [result], planned: 3, warmups: [], judgments: {}, message: "",
    conditions: { chat: { model: "test-model", digest: "a".repeat(64) }, corpusVersion: "b".repeat(64), datasetVersion: "c".repeat(64), inputBudget: 3584, cacheEvidence: "NOT_MEASURED" } },
    summary: [{ mode: "CHAT", completed: 1, noEvidence: 0, failed: 0, judged: 0, meanAnswerScore: null,
      totalMs: { n: 1, median: 50 }, endToEndTtftMs: { n: 1, median: 20 } }] };
}
function api(overrides = {}) { return { list: async () => [], start: async () => ({ id }), get: async () => payload(), cancel: async () => ({}), judge: async () => ({}), ...overrides }; }

test("comparison renders source/question/answer as literal text and no automatic score", () => {
  const dom = page(); const document = dom.window.document; renderComparison(document, payload());
  assert.equal(document.querySelectorAll("#evaluation-results img, #evaluation-results script, #evaluation-results svg").length, 0);
  assert.match(document.getElementById("evaluation-results").textContent, /<script>answer/);
  assert.match(document.getElementById("evaluation-results").textContent, /Ручная оценка не выполнена/);
  assert.match(document.getElementById("evaluation-summary").textContent, /—/);
  assert.equal(document.getElementById("evaluation-export").getAttribute("href"), `/api/evaluation/runs/${id}/report.md`); dom.window.close();
});
test("no-model result displays missing TTFT and clears prior rows", () => {
  const dom = page(); const data = payload(); renderComparison(dom.window.document, data);
  data.run.results[0].outcome = "NO_EVIDENCE"; data.run.results[0].timing.modelTtftMs = null;
  data.summary[0].endToEndTtftMs = { n: 0, median: null }; renderComparison(dom.window.document, data);
  assert.equal(dom.window.document.querySelectorAll("#evaluation-results li").length, 1);
  assert.match(dom.window.document.getElementById("evaluation-results").textContent, /TTFT модели —/); dom.window.close();
});
test("one active start locks settings and polls until terminal state", async () => {
  const dom = page(); const callbacks = []; let state = "RUNNING"; let starts = 0;
  const view = mountEvaluation(dom.window.document, { api: api({ start: async () => { starts++; return { id }; }, get: async () => payload(state) }), schedule: fn => { callbacks.push(fn); return 1; }, unschedule() {} });
  await view.ready; await view.start(); assert.equal(await view.start(), false); assert.equal(starts, 1);
  assert.equal(dom.window.document.getElementById("evaluation-controls").disabled, true);
  state = "COMPLETED"; await callbacks[0](); assert.equal(dom.window.document.getElementById("evaluation-controls").disabled, false);
  assert.equal(dom.window.document.getElementById("evaluation-cancel").disabled, true); view.dispose(); dom.window.close();
});
test("cancel targets selected run and preserves displayed results", async () => {
  const dom = page(); let cancelled; const view = mountEvaluation(dom.window.document, { api: api({ get: async () => payload("RUNNING"), cancel: async run => { cancelled = run; } }), schedule: () => 1, unschedule() {} });
  await view.ready; await view.start(); assert.equal(await view.cancel(), true); assert.equal(cancelled, id);
  assert.match(dom.window.document.getElementById("evaluation-notice").textContent, /сохранены/); view.dispose(); dom.window.close();
});
test("manual review requires both chosen scores and does not default to full credit", async () => {
  const dom = page(); let sent; const view = mountEvaluation(dom.window.document, { api: api({ judge: async (...args) => { sent = args; } }) });
  await view.ready; await view.start(); assert.equal(await view.review(), false); assert.equal(sent, undefined);
  dom.window.document.getElementById("evaluation-answer-score").value = "1"; dom.window.document.getElementById("evaluation-citation-score").value = "0";
  dom.window.document.getElementById("evaluation-notes").value = "checked evidence";
  assert.equal(await view.review(), true); assert.equal(sent[0], id); assert.equal(sent[1], 0);
  assert.deepEqual(sent[2], { answer: 1, citations: 0, refusalCorrect: null, notes: "checked evidence", reviewedAt: "" }); view.dispose(); dom.window.close();
});
test("changing split clears development IDs and submits explicit heldout selection", async () => {
  const dom = page(); let request; const view = mountEvaluation(dom.window.document, { api: api({ start: async body => { request = body; return { id }; } }) });
  await view.ready; const split = dom.window.document.getElementById("evaluation-split"); split.value = "HOLDOUT"; split.dispatchEvent(new dom.window.Event("change"));
  await view.start(); assert.equal(request.split, "HOLDOUT"); assert.deepEqual(request.caseIds, []); assert.deepEqual(request.modes, ["CHAT", "FULL_CONTEXT", "RAG"]);
  view.dispose(); dom.window.close();
});
test("late restore cannot replace a newly started run", async () => {
  const dom = page(); const pending = deferred(); const reads = []; const view = mountEvaluation(dom.window.document, { api: api({ list: () => pending.promise, get: async run => { reads.push(run); return payload(); } }) });
  await view.start(); pending.resolve([{ id: "old-run" }]); await view.ready; assert.deepEqual(reads, [id]); view.dispose(); dom.window.close();
});
test("restores latest saved report without starting new model requests", async () => {
  const dom = page(); let starts = 0; const view = mountEvaluation(dom.window.document, { api: api({ list: async () => [{ id }], start: async () => { starts++; return { id }; } }) });
  await view.ready; assert.equal(starts, 0); assert.match(dom.window.document.getElementById("evaluation-status").textContent, /COMPLETED/); view.dispose(); dom.window.close();
});
test("HTTP helper preserves parameters and sends judgments with PUT", async () => {
  const calls = []; const client = createEvaluationApi(async (url, options) => { calls.push({ url, ...options }); return Response.json({ id }); });
  await client.start({ split: "DEV", repeats: 2 }); await client.judge(id, 4, { answer: 1 });
  assert.equal(calls[0].url, "/api/evaluation/runs"); assert.deepEqual(JSON.parse(calls[0].body), { split: "DEV", repeats: 2 });
  assert.equal(calls[1].method, "PUT"); assert.equal(calls[1].url, `/api/evaluation/runs/${id}/judgments/4`);
});
test("HTTP helper gives stable errors and start unlocks after rejection", async () => {
  await assert.rejects(createEvaluationApi(async () => { throw new Error("internal"); }).list(), /Нет связи/);
  const dom = page(); const view = mountEvaluation(dom.window.document, { api: api({ start: async () => { throw new Error("Нет индекса"); } }) });
  await view.ready; assert.equal(await view.start(), false); assert.equal(dom.window.document.getElementById("evaluation-controls").disabled, false);
  assert.equal(dom.window.document.getElementById("evaluation-error").textContent, "Нет индекса"); view.dispose(); dom.window.close();
});
