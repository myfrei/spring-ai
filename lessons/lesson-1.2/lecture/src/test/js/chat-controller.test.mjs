import test from "node:test";
import assert from "node:assert/strict";
import { createChatController, validateMessage } from "../../main/resources/static/js/chat-controller.js";
import { deferred } from "./fixtures.mjs";

test("starts in idle and publishes the initial state", () => {
  const states = [];
  const controller = createChatController({ send: async () => "Ответ", onStateChange: state => states.push(state) });
  assert.deepEqual(controller.getState(), { phase: "idle", messages: [], error: "" });
  assert.equal(states.length, 1);
});

test("validates blank and oversized input before calling API", async () => {
  let calls = 0;
  const controller = createChatController({ send: async () => { calls++; return "Ответ"; } });
  for (const value of ["", "  ", "\n\t", "x".repeat(4001)]) {
    assert.equal(await controller.submit(value), false);
    assert.equal(controller.getState().phase, "error");
  }
  assert.equal(calls, 0);
  assert.deepEqual(controller.getState().messages, []);
  assert.equal(validateMessage("x".repeat(4000)), "");
});

test("transitions idle to submitting to success and preserves roles", async () => {
  const phases = [];
  const controller = createChatController({ send: async text => {
    assert.equal(text, "Вопрос");
    return "Ответ";
  }, onStateChange: state => phases.push(state.phase) });
  assert.equal(await controller.submit("  Вопрос  "), true);
  assert.deepEqual(phases, ["idle", "submitting", "success"]);
  assert.deepEqual(controller.getState().messages, [
    { role: "user", content: "Вопрос" }, { role: "assistant", content: "Ответ" }
  ]);
});

test("ignores a second send while the first request is pending", async () => {
  const pending = deferred();
  let calls = 0;
  const controller = createChatController({ send: () => { calls++; return pending.promise; } });
  const first = controller.submit("Первый");
  assert.equal(controller.getState().phase, "submitting");
  assert.equal(await controller.submit("Второй"), false);
  assert.equal(calls, 1);
  pending.resolve("Ответ");
  assert.equal(await first, true);
});

test("failed request never appends a fake assistant answer", async () => {
  const controller = createChatController({ send: async () => { throw new Error("Модель недоступна"); } });
  assert.equal(await controller.submit("Вопрос"), false);
  const state = controller.getState();
  assert.equal(state.phase, "error");
  assert.equal(state.error, "Модель недоступна");
  assert.deepEqual(state.messages, [{ role: "user", content: "Вопрос" }]);
});

test("a fresh request after failure clears the old error", async () => {
  let calls = 0;
  const controller = createChatController({ send: async () => {
    if (++calls === 1) throw new Error("Временная ошибка");
    return "Ответ";
  } });
  await controller.submit("Вопрос");
  assert.equal(await controller.submit("Новый вопрос"), true);
  assert.equal(controller.getState().error, "");
  assert.equal(controller.getState().phase, "success");
});

test("returned snapshots cannot mutate controller messages", async () => {
  const controller = createChatController({ send: async () => "Ответ" });
  await controller.submit("Вопрос");
  const snapshot = controller.getState();
  snapshot.messages[0].content = "Подмена";
  snapshot.messages.push({ role: "assistant", content: "Подмена" });
  assert.equal(controller.getState().messages[0].content, "Вопрос");
  assert.equal(controller.getState().messages.length, 2);
});
