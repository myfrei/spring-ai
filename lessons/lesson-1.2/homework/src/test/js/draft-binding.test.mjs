import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";
import { mountDraftChat } from "../../main/resources/static/js/draft-app.js";

test("provided binding keeps chat working when the localStorage getter is denied", async t => {
  const dom = new JSDOM(
    readFileSync(new URL("../../main/resources/static/draft.html", import.meta.url), "utf8"),
    { url: "http://localhost/" }
  );
  t.after(() => dom.window.close());
  const previous = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
  Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    get() { throw new Error("SecurityError: storage access denied"); }
  });
  try {
    let calls = 0;
    const controller = mountDraftChat(dom.window.document, async message => {
      calls++;
      assert.equal(message, "Вопрос без storage");
      return "Ответ без storage";
    });
    assert.equal(await controller.submit("Вопрос без storage"), true);
    assert.equal(calls, 1);
    assert.match(dom.window.document.body.textContent, /Ответ без storage/);
  } finally {
    if (previous) Object.defineProperty(globalThis, "localStorage", previous);
    else delete globalThis.localStorage;
  }
});
