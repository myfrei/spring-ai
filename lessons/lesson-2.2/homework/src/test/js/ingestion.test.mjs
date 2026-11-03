import test from "node:test";
import assert from "node:assert/strict";
import { mountIngestion, createIngestionApi, renderChunks } from "../../main/resources/static/js/ingestion.js";
import { page, deferred } from "./fixtures.mjs";

const options = { strategy: "MARKDOWN_SECTIONS", maxCodePoints: 600, overlapCodePoints: 80 };
const chunk = { id: "a".repeat(64), documentId: "overview", path: "README.md", section: "TaskShelf > Run",
  startLine: 3, endLine: 8, text: "# Run\nJava" };
const result = (extra = {}) => ({ reused: false, fingerprint: "f".repeat(64), corpusVersion: "c".repeat(64), chunkCount: 12,
  embedding: { model: "fixture-embed:1", digest: "d".repeat(64), dimensions: 3 }, ...extra });
function fixture(t, overrides = {}) {
  const dom = page(); t.after(() => dom.window.close());
  const api = { status: async () => ({ indexed: false }), preview: async () => ({ corpusVersion: "c".repeat(64), options, chunks: [chunk] }),
    index: async () => result(), ...overrides };
  return { dom, document: dom.window.document, api, controller: mountIngestion(dom.window.document, api) };
}

test("empty index starts with available controls and no automatic indexing", async t => {
  let called = false; const f = fixture(t, { index: async () => { called = true; return result(); } });
  await f.controller.ready;
  assert.equal(called, false); assert.equal(f.document.getElementById("ingestion-controls").disabled, false);
  assert.match(f.document.getElementById("ingestion-status").textContent, /индекса ещё нет/);
});
test("preview sends selected chunk parameters and renders source line metadata", async t => {
  let sent; const f = fixture(t, { preview: async value => { sent = value; return { corpusVersion: "c".repeat(64), chunks: [chunk] }; } });
  await f.controller.ready; f.document.getElementById("chunk-size").value = "300";
  await f.controller.preview(); assert.deepEqual(sent, { ...options, maxCodePoints: 300 });
  assert.match(f.document.querySelector("#chunk-list summary").textContent, /README.md:3–8/);
  assert.equal(f.document.querySelector("#chunk-list a").getAttribute("href"), "/api/corpus/documents/overview");
  assert.match(f.document.getElementById("ingestion-status").textContent, /Embeddings не запрашивались/);
});
test("chunk text is literal and preview displays at most twenty items", t => {
  const dom = page(); t.after(() => dom.window.close());
  const text = '<img src=x onerror="alert(1)">';
  renderChunks(dom.window.document, { chunks: Array.from({ length: 25 }, () => ({ ...chunk, text })) });
  assert.equal(dom.window.document.querySelectorAll("#chunk-list li").length, 20);
  assert.equal(dom.window.document.querySelectorAll("#chunk-list img").length, 0);
  assert.equal(dom.window.document.querySelector("#chunk-list pre").textContent, text);
});
test("pending operation locks fields and suppresses duplicate index request", async t => {
  const pending = deferred(); let indexes = 0;
  const f = fixture(t, { preview: () => pending.promise, index: async () => { indexes++; return result(); } });
  await f.controller.ready; const running = f.controller.preview();
  assert.equal(f.document.getElementById("ingestion-controls").disabled, true);
  assert.equal(await f.controller.index(), false); assert.equal(indexes, 0);
  pending.resolve({ corpusVersion: "c".repeat(64), chunks: [] }); await running;
  assert.equal(f.document.getElementById("ingestion-controls").disabled, false);
});
test("index reports actual identity dimension and subsequent reuse without exposing vectors", async t => {
  let calls = 0; const f = fixture(t, { index: async () => result({ reused: calls++ > 0 }) });
  await f.controller.ready; await f.controller.index();
  let text = f.document.getElementById("ingestion-status").textContent;
  assert.match(text, /12 чанков/); assert.match(text, /fixture-embed:1/); assert.match(text, /3 измерений/);
  await f.controller.index(); text = f.document.getElementById("ingestion-status").textContent;
  assert.match(text, /переиспользован без пересчёта/); assert.match(text, /поиск ещё не подключён/);
});
test("failed rebuild keeps previous status visible and unlocks UI", async t => {
  let calls = 0; const f = fixture(t, { index: async () => { if (calls++) throw new Error("Модель недоступна"); return result(); } });
  await f.controller.ready; await f.controller.index(); const previous = f.document.getElementById("ingestion-status").textContent;
  assert.equal(await f.controller.index(), false);
  assert.equal(f.document.getElementById("ingestion-status").textContent, previous);
  assert.equal(f.document.getElementById("ingestion-error").textContent, "Модель недоступна");
  assert.equal(f.document.getElementById("ingestion-controls").disabled, false);
});
test("offline saved status identifies differing configuration without claiming runtime check", async t => {
  const f = fixture(t, { status: async () => ({ indexed: true, saved: result(), currentCorpusVersion: "changed",
    configuredModel: "fixture-embed:2", configuredDimensions: 8 }) });
  await f.controller.ready;
  assert.match(f.document.getElementById("ingestion-status").textContent, /требуется перестроение/);
  assert.match(f.document.getElementById("ingestion-status").textContent, /Digest runtime сейчас не проверялся/);
});
test("API sends JSON options to explicit ingestion route", async () => {
  let called; const api = createIngestionApi(async (path, value) => { called = { path, value }; return new Response(JSON.stringify(result())); });
  await api.index(options); assert.equal(called.path, "/api/ingestion/index");
  assert.equal(called.value.method, "POST"); assert.deepEqual(JSON.parse(called.value.body), options);
});
test("API reports provider detail or stable network message", async () => {
  const unavailable = createIngestionApi(async () => new Response(JSON.stringify({ detail: "Embedding-модель не найдена" }), { status: 503 }));
  await assert.rejects(unavailable.index(options), /Embedding-модель не найдена/);
  const disconnected = createIngestionApi(async () => { throw new Error("private-url"); });
  await assert.rejects(disconnected.status(), /Нет связи с приложением/);
});
