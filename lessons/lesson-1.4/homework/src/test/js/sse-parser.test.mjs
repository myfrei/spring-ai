import test from "node:test";
import assert from "node:assert/strict";
import { createSseParser, SseProtocolError } from "../../main/resources/static/js/sse-parser.js";

test("parses a frame split at every character", () => {
  const parser = createSseParser(); const frames = [];
  for (const character of 'event: delta\ndata: {"text":"Привет"}\n\n') frames.push(...parser.push(character));
  assert.deepEqual(frames, [{ event: "delta", data: '{"text":"Привет"}' }]);
  assert.deepEqual(parser.finish(), []);
});
test("handles CRLF across chunks, comments and multiline data", () => {
  const parser = createSseParser();
  assert.deepEqual(parser.push(": keepalive\r"), []);
  const frames = parser.push("\n\r\nevent:delta\r\ndata:first\r\ndata: second\r\n\r\n");
  assert.deepEqual(frames, [{ event: "delta", data: "first\nsecond" }]);
});
test("supports lone CR including the last delimiter", () => {
  const parser = createSseParser();
  const frames = [...parser.push("event:delta\rdata:x\r\r"), ...parser.finish()];
  assert.deepEqual(frames, [{ event: "delta", data: "x" }]);
});
test("dispatches several coalesced frames in order", () => {
  const parser = createSseParser();
  assert.deepEqual(parser.push("event:a\ndata:1\n\nevent:b\ndata:2\n\n"), [
    { event: "a", data: "1" }, { event: "b", data: "2" }
  ]);
});
test("does not invent an event at unfinished EOF", () => {
  const parser = createSseParser();
  parser.push("event:delta\ndata:unfinished\n");
  assert.throws(() => parser.finish(), SseProtocolError);
});
test("bounds an unterminated line", () => {
  assert.throws(() => createSseParser(16).push("data:" + "x".repeat(20)), SseProtocolError);
});
test("bounds accumulated data across multiple lines", () => {
  const parser = createSseParser(24);
  parser.push("data:" + "x".repeat(15) + "\n");
  assert.throws(() => parser.push("data:" + "x".repeat(15) + "\n"), SseProtocolError);
});
test("frame bound does not reject many small frames in one read", () => {
  assert.equal(createSseParser(16).push("event:a\ndata:x\n\n".repeat(20)).length, 20);
});
