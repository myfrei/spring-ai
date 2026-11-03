import test from "node:test";
import assert from "node:assert/strict";
import { renderDiagnostics, renderTrace, mountDiagnostics, createDiagnosticApi } from "../../main/resources/static/js/diagnostics.js";
import { page, deferred } from "./fixtures.mjs";
const id = "123e4567-e89b-12d3-a456-426614174000";
function report(origin = "SYNTHETIC_LAB") { return { runId: id, origin, rulesVersion: "rules-v1", baselineCompatible: true, note: "Fixture only", rows: [
  { ordinal: 0, caseId: "dev-01", mode: "RAG", outcome: "COMPLETED", traceId: id, traceAvailable: true, traceWarning: "",
    finding: { label: "<img src=x onerror=alert(1)>", facts: ["Fact"], hypothesis: "<script>bad</script>", nextExperiment: "Change one variable" }, sampleQuestion: "Q", sampleAnswer: "<svg onload=alert(1)>" }] }; }
function trace() { return { id, context: { synthetic: true }, outcome: "COMPLETED", events: [{ sequence: 1, stage: "GENERATION", status: "SKIPPED", elapsedMs: 5, code: "SCRIPTED_RESPONSE", counts: {} }], sources: [] }; }
test("diagnostic findings keep facts hypotheses and fixture text literal", () => {
 const dom = page(); renderDiagnostics(dom.window.document, report());
 assert.equal(dom.window.document.querySelectorAll("#diagnostic-findings img, #diagnostic-findings script, #diagnostic-findings svg").length, 0);
 assert.match(dom.window.document.getElementById("diagnostic-findings").textContent, /Гипотеза:/);
 assert.match(dom.window.document.getElementById("diagnostic-status").textContent, /без вызова LLM/); assert.equal(dom.window.document.getElementById("diagnostic-export").hidden, true); dom.window.close();
});
test("experiment report has encoded export and unavailable traces are disabled", () => {
 const dom = page(); const data = report("EXPERIMENT"); data.rows[0].traceAvailable = false; data.rows[0].traceWarning = "TRACE_NOT_RECORDED";
 renderDiagnostics(dom.window.document, data);
 assert.equal(dom.window.document.querySelector("#diagnostic-findings button").disabled, true);
 assert.equal(dom.window.document.getElementById("diagnostic-export").getAttribute("href"), `/api/diagnostics/runs/${id}/report.md`); dom.window.close();
});
test("trace renderer shows explicit skipped stage without rendering HTML", () => {
 const dom = page(); const data = trace(); data.events[0].code = "<img src=x>"; renderTrace(dom.window.document, data);
 assert.equal(dom.window.document.querySelector("#diagnostic-trace img"), null); assert.match(dom.window.document.getElementById("diagnostic-trace").textContent, /SKIPPED/); dom.window.close();
});
test("lab request locks both buttons and prevents duplicate invocation", async () => {
 const dom = page(); const wait = deferred(); let calls = 0;
 const view = mountDiagnostics(dom.window.document, { lab: async () => { calls++; await wait.promise; return report(); } });
 const first = view.lab(); assert.equal(await view.lab(), false); assert.equal(calls, 1); wait.resolve(); assert.equal(await first, true);
 assert.equal(dom.window.document.getElementById("diagnostic-lab").disabled, false); dom.window.close();
});
test("trace button requests the matching id", async () => {
 const dom = page(); const done = deferred(); let selected;
 const view = mountDiagnostics(dom.window.document, { lab: async () => report(), trace: async value => { selected = value; done.resolve(); return trace(); } });
 await view.lab(); dom.window.document.querySelector("#diagnostic-findings button").click(); await done.promise;
 assert.equal(selected, id); dom.window.close();
});
test("stale trace response cannot overwrite another report", async () => {
 const dom = page(); const wait = deferred(); const view = mountDiagnostics(dom.window.document, { lab: async () => report(), latest: async () => report("EXPERIMENT"), trace: () => wait.promise });
 await view.lab(); const pending = view.inspect(id); await view.latest(); wait.resolve(trace()); await pending;
 assert.equal(dom.window.document.getElementById("diagnostic-trace").children.length, 0); dom.window.close();
});
test("failed analysis unlocks controls and preserves public explanation", async () => {
 const dom = page(); const view = mountDiagnostics(dom.window.document, { latest: async () => { throw new Error("Сначала выполните эксперимент"); } });
 assert.equal(await view.latest(), false); assert.match(dom.window.document.getElementById("diagnostic-error").textContent, /Сначала/);
 assert.equal(dom.window.document.getElementById("diagnostic-latest").disabled, false); dom.window.close();
});
test("API separates laboratory POST from read-only trace and handles network error", async () => {
 const calls = []; const api = createDiagnosticApi(async (url, options) => { calls.push({ url, method: options.method }); return Response.json({}); });
 await api.lab(); await api.trace(id); assert.deepEqual(calls, [{ url: "/api/diagnostics/lab", method: "POST" }, { url: `/api/diagnostics/traces/${id}`, method: "GET" }]);
 await assert.rejects(createDiagnosticApi(async () => { throw new Error("internal"); }).latest(), /Нет связи/);
});
