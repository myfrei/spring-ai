import test from "node:test";
import assert from "node:assert/strict";
import { streamMessage } from "../../main/resources/static/js/stream-api.js";
import { frame, streamResponse } from "./fixtures.mjs";

const success = () => frame("started") + frame("delta", { text: "Привет 👋" }) + frame("completed");

test("POSTs JSON and the abort signal to the application and consumes events", async () => {
  const abort = new AbortController(); const events = [];
  await streamMessage('Вопрос {"a":"<b>"}', { signal: abort.signal, onEvent: e => events.push(e) }, async (url, options) => {
    assert.equal(url, "/api/chat/stream"); assert.equal(options.method, "POST");
    assert.equal(options.signal, abort.signal);
    assert.deepEqual(JSON.parse(options.body), { message: 'Вопрос {"a":"<b>"}' });
    assert.match(options.headers.Accept, /text\/event-stream/);
    return streamResponse([success()]);
  });
  assert.deepEqual(events.map(e => e.type), ["started", "delta", "completed"]);
});
test("decodes Cyrillic and emoji split into individual UTF-8 bytes", async () => {
  const bytes = new TextEncoder().encode(success()); const events = [];
  await streamMessage("Вопрос", { onEvent: e => events.push(e) }, async () =>
    streamResponse([...bytes].map(byte => new Uint8Array([byte]))));
  assert.equal(events[1].text, "Привет 👋");
});
test("cancels the reader immediately after completed instead of waiting for socket EOF", async () => {
  let cancelled = false;
  await streamMessage("Вопрос", {}, async () => streamResponse([success()], { close: false, cancel: () => { cancelled = true; } }));
  assert.equal(cancelled, true);
});
test("normal EOF without completed remains an incomplete stream", async () => {
  const events = [];
  await assert.rejects(streamMessage("Вопрос", { onEvent: e => events.push(e) }, async () =>
    streamResponse([frame("started"), frame("delta", { text: "Часть" })])), { code: "INCOMPLETE_STREAM" });
  assert.equal(events[1].text, "Часть");
  assert.ok(!events.some(e => e.type === "completed"));
});
test("failed event inside HTTP 200 rejects with the public error code", async () => {
  await assert.rejects(streamMessage("Вопрос", {}, async () => streamResponse([
    frame("started"), frame("delta", { text: "Часть" }),
    frame("failed", { code: "MODEL_ERROR", message: "Поток модели прерван." })
  ])), { code: "MODEL_ERROR", status: 200, message: "Поток модели прерван." });
});
test("HTTP error before streaming preserves ProblemDetail and makes one request", async () => {
  let requests = 0;
  await assert.rejects(streamMessage("Вопрос", {}, async () => {
    requests++; return Response.json({ detail: "Неверный ввод" }, { status: 400 });
  }), { code: "HTTP_ERROR", status: 400, message: "Неверный ввод" });
  assert.equal(requests, 1);
});
test("HTTP 200 JSON is not accepted as a stream", async () => {
  await assert.rejects(streamMessage("Вопрос", {}, async () => Response.json({ answer: "Ответ" })), { code: "PROTOCOL_ERROR" });
});
test("network error is distinct from an empty successful response", async () => {
  await assert.rejects(streamMessage("Вопрос", {}, async () => { throw new TypeError("fetch failed"); }), { code: "NETWORK" });
});
for (const [name, wire] of [
  ["delta before started", frame("delta", { text: "x" })],
  ["duplicate started", frame("started") + frame("started")],
  ["unknown event", 'event:mystery\ndata:{"type":"mystery"}\n\n'],
  ["mismatching event and payload", 'event:delta\ndata:{"type":"completed"}\n\n'],
  ["malformed JSON", 'event:started\ndata:{broken}\n\n'],
  ["empty completed", frame("started") + frame("completed")],
  ["unfinished SSE frame", frame("started") + 'event:delta\ndata:{"type":"delta","text":"x"}']
]) {
  test(`rejects protocol violation: ${name}`, async () => {
    await assert.rejects(streamMessage("Вопрос", {}, async () => streamResponse([wire])), { code: "PROTOCOL_ERROR" });
  });
}
test("invalid UTF-8 is not silently replaced with corrupted text", async () => {
  await assert.rejects(streamMessage("Вопрос", {}, async () => streamResponse([new Uint8Array([0xff])])), { code: "PROTOCOL_ERROR" });
});
test("read failure after a delta preserves delivered text and rejects", async () => {
  let source; const events = [];
  const response = new Response(new ReadableStream({ start(controller) {
    source = controller; controller.enqueue(new TextEncoder().encode(frame("started") + frame("delta", { text: "Часть" })));
  } }), { headers: { "Content-Type": "text/event-stream" } });
  await assert.rejects(streamMessage("Вопрос", { onEvent: e => {
    events.push(e); if (e.type === "delta") source.error(new Error("socket closed"));
  } }, async () => response), { code: "CONNECTION_LOST" });
  assert.equal(events[1].text, "Часть");
});
test("pre-aborted request never starts fetch", async () => {
  const abort = new AbortController(); abort.abort(); let calls = 0;
  await assert.rejects(streamMessage("Вопрос", { signal: abort.signal }, async () => { calls++; }), { name: "AbortError" });
  assert.equal(calls, 0);
});
test("abort while reading is classified as cancellation", async () => {
  const abort = new AbortController();
  const response = new Response(new ReadableStream({ start(controller) {
    controller.enqueue(new TextEncoder().encode(frame("started")));
    abort.signal.addEventListener("abort", () => controller.error(new DOMException("abort", "AbortError")), { once: true });
  } }), { headers: { "Content-Type": "text/event-stream" } });
  await assert.rejects(streamMessage("Вопрос", { signal: abort.signal, onEvent: () => abort.abort() }, async () => response),
    { name: "AbortError" });
});
