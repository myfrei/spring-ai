import test from "node:test";
import assert from "node:assert/strict";
import { mountChat } from "../../main/resources/static/js/app.js";
import { createChatView } from "../../main/resources/static/js/chat-view.js";
import { page, enter, submit, tick, deferred } from "./fixtures.mjs";

test("initial page exposes a labelled input and an idle status", t => {
  const dom = page(); t.after(() => dom.window.close());
  mountChat(dom.window.document, async () => "Ответ");
  const doc = dom.window.document;
  assert.equal(doc.querySelector('label[for="message"]').textContent, "Ваш вопрос");
  assert.equal(doc.getElementById("status").textContent, "Готов к вопросу");
  assert.equal(doc.getElementById("empty-state").hidden, false);
  assert.equal(doc.getElementById("error-box").hidden, true);
});

test("form submission displays answer, clears input and updates the counter", async t => {
  const dom = page(); t.after(() => dom.window.close());
  let calls = 0;
  mountChat(dom.window.document, async text => { calls++; assert.equal(text, "Вопрос"); return "Ответ"; });
  enter(dom, "Вопрос"); submit(dom); await tick();
  const doc = dom.window.document;
  assert.equal(calls, 1);
  assert.equal(doc.getElementById("messages").children.length, 2);
  assert.match(doc.getElementById("messages").textContent, /Ответ/);
  assert.equal(doc.getElementById("message").value, "");
  assert.equal(doc.getElementById("character-count").textContent, "0 / 4000");
  assert.equal(doc.getElementById("status").textContent, "Ответ получен");
});

test("pending request locks controls and blocks duplicate submits", async t => {
  const dom = page(); t.after(() => dom.window.close());
  const pending = deferred(); let calls = 0;
  mountChat(dom.window.document, () => { calls++; return pending.promise; });
  enter(dom, "Вопрос"); submit(dom); submit(dom);
  const doc = dom.window.document;
  assert.equal(doc.getElementById("send-button").disabled, true);
  assert.equal(doc.getElementById("message").disabled, true);
  assert.equal(doc.getElementById("chat-form").getAttribute("aria-busy"), "true");
  assert.equal(calls, 1);
  pending.resolve("Ответ"); await tick();
  assert.equal(doc.getElementById("send-button").disabled, false);
});

test("invalid input shows an alert without contacting the API", async t => {
  const dom = page(); t.after(() => dom.window.close());
  let calls = 0;
  mountChat(dom.window.document, async () => { calls++; return "Ответ"; });
  enter(dom, "   "); submit(dom); await tick();
  assert.equal(calls, 0);
  assert.equal(dom.window.document.getElementById("error-box").hidden, false);
  assert.match(dom.window.document.getElementById("error-message").textContent, /Введите вопрос/);
});

test("model HTML and code are literal text and create no active elements", t => {
  const dom = page(); t.after(() => dom.window.close());
  const doc = dom.window.document;
  const dangerous = '<img src=x onerror="globalThis.pwned=true">\n<script>alert(1)</script>\nif (a < b) {}';
  createChatView(doc).render({ phase: "success", error: "", messages: [
    { role: "user", content: dangerous }, { role: "assistant", content: dangerous }
  ] });
  assert.equal(doc.querySelectorAll("#messages img, #messages script").length, 0);
  assert.deepEqual([...doc.querySelectorAll("#messages pre")].map(node => node.textContent), [dangerous, dangerous]);
});

test("failure preserves input, unlocks form and allows a new attempt", async t => {
  const dom = page(); t.after(() => dom.window.close());
  let calls = 0;
  mountChat(dom.window.document, async () => {
    if (++calls === 1) throw new Error("Модель недоступна");
    return "Ответ после восстановления";
  });
  enter(dom, "Вопрос"); submit(dom); await tick();
  const doc = dom.window.document;
  assert.equal(doc.getElementById("message").value, "Вопрос");
  assert.equal(doc.getElementById("send-button").disabled, false);
  assert.equal(doc.getElementById("error-message").textContent, "Модель недоступна");
  submit(dom); await tick();
  assert.equal(calls, 2);
  assert.equal(doc.getElementById("error-box").hidden, true);
  assert.match(doc.getElementById("messages").textContent, /Ответ после восстановления/);
});
