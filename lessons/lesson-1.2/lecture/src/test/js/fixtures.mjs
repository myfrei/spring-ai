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
