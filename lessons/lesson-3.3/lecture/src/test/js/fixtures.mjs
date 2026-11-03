import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";

export function page() {
  const html = readFileSync(new URL("../../main/resources/static/index.html", import.meta.url), "utf8");
  return new JSDOM(html, { url: "http://localhost/" });
}

export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

export function enter(dom, message) {
  const input = dom.window.document.getElementById("message");
  input.value = message;
  input.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
}

export function submit(dom) {
  dom.window.document.getElementById("chat-form")
    .dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
}

export const tick = () => new Promise(resolve => setImmediate(resolve));

export function frame(type, fields = {}) {
  return `event:${type}\ndata:${JSON.stringify({ type, text: "", code: "", message: "", ...fields })}\n\n`;
}
export function streamResponse(chunks, { close = true, cancel = () => {} } = {}) {
  return new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(typeof chunk === "string" ? new TextEncoder().encode(chunk) : chunk);
      if (close) controller.close();
    }, cancel
  }), { headers: { "Content-Type": "text/event-stream; charset=UTF-8" } });
}
export async function completeSend(message, { onEvent }) {
  onEvent({ type: "started" });
  onEvent({ type: "delta", text: "Ответ" });
  onEvent({ type: "completed" });
}
