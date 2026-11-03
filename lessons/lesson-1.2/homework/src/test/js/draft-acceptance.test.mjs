import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";
import { createDraftStore } from "../../main/resources/static/js/draft-store.js";
import { mountDraftChat } from "../../main/resources/static/js/draft-app.js";
const key = "repopilot.draft.v1";
function storage() { const data = new Map(); return { getItem: k => data.get(k) ?? null, setItem: (k,v) => data.set(k,v), removeItem: k => data.delete(k), data }; }
function page() { return new JSDOM(readFileSync(new URL("../../main/resources/static/draft.html", import.meta.url), "utf8"), { url:"http://localhost/" }); }
const tick = () => new Promise(resolve => setImmediate(resolve));
test("draft survives a fresh store without executing HTML", () => {
  const backend = storage(); const first = createDraftStore(backend); assert.equal(first.save("<script>Вопрос</script>"), true);
  assert.equal(createDraftStore(backend).read(), "<script>Вопрос</script>"); assert.deepEqual(JSON.parse(backend.getItem(key)), { version:1,text:"<script>Вопрос</script>" });
});
test("corrupt, future-version and oversized storage values are ignored", () => {
  const backend = storage(); const store = createDraftStore(backend);
  for (const raw of ["broken", JSON.stringify({ version:2,text:"Q" }), JSON.stringify({ version:1,text:"x".repeat(4001) }), JSON.stringify({ version:1,text:42 })]) { backend.setItem(key,raw); assert.equal(store.read(), ""); }
  assert.equal(store.save("x".repeat(4001)), false); assert.equal(store.save(null), false);
});
test("storage denial is a fallback, never an unhandled chat error", () => {
  const blocked = { getItem(){throw new Error("blocked");}, setItem(){throw new Error("blocked");}, removeItem(){throw new Error("blocked");} };
  const store = createDraftStore(blocked); assert.equal(store.read(), ""); assert.equal(store.save("Q"),false); assert.equal(store.clearIfUnchanged("Q"),false);
});
test("compare-and-clear preserves a newer draft from another tab", () => {
  const backend = storage(); const store = createDraftStore(backend); store.save("sent"); store.save("newer");
  assert.equal(store.clearIfUnchanged("sent"),false); assert.equal(store.read(),"newer");
  assert.equal(store.clearIfUnchanged("newer"),true); assert.equal(backend.getItem(key),null);
});
test("draft page restores the input and removes only successfully sent text", async t => {
  const backend = storage(); createDraftStore(backend).save("Сохранённый вопрос"); const dom=page();t.after(()=>dom.window.close());
  mountDraftChat(dom.window.document, async()=>"Ответ",backend);
  const input=dom.window.document.getElementById("message");assert.equal(input.value,"Сохранённый вопрос");
  dom.window.document.getElementById("chat-form").dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true}));await tick();
  assert.equal(input.value,"");assert.equal(backend.getItem(key),null);
});
test("failed send keeps the restored draft for recovery", async t => {
  const backend=storage();createDraftStore(backend).save("Повторить позже");const dom=page();t.after(()=>dom.window.close());
  mountDraftChat(dom.window.document,async()=>{throw new Error("offline");},backend);
  dom.window.document.getElementById("chat-form").dispatchEvent(new dom.window.Event("submit",{bubbles:true,cancelable:true}));await tick();
  assert.equal(createDraftStore(backend).read(),"Повторить позже");assert.equal(dom.window.document.getElementById("message").value,"Повторить позже");
});
