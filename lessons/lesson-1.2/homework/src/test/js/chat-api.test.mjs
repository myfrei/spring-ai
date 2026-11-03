import test from "node:test";
import assert from "node:assert/strict";
import { sendMessage, ChatApiError } from "../../main/resources/static/js/chat-api.js";

test("sends POST to the application with JSON body and returns answer", async () => {
  const fetchImpl = async (url, options) => {
    assert.equal(url, "/api/chat");
    assert.equal(options.method, "POST");
    assert.equal(options.headers["Content-Type"], "application/json");
    assert.deepEqual(JSON.parse(options.body), { message: "Что такое record?" });
    return Response.json({ answer: "Класс данных." });
  };
  assert.equal(await sendMessage("Что такое record?", fetchImpl), "Класс данных.");
});

test("HTTP 503 is an error even when fetch resolves normally", async () => {
  const fetchImpl = async () => Response.json({ detail: "Модель временно недоступна." }, { status: 503 });
  await assert.rejects(sendMessage("Вопрос", fetchImpl), error => {
    assert.ok(error instanceof ChatApiError);
    assert.equal(error.status, 503);
    assert.equal(error.message, "Модель временно недоступна.");
    return true;
  });
});

test("HTTP 400 preserves the public validation detail", async () => {
  const fetchImpl = async () => Response.json({ detail: "Нужен текст вопроса." }, { status: 400 });
  await assert.rejects(sendMessage("Вопрос", fetchImpl), { message: "Нужен текст вопроса.", status: 400 });
});

test("unexpected HTTP error does not expose internal detail", async () => {
  const fetchImpl = async () => Response.json({ detail: "private-internal-stack" }, { status: 500 });
  await assert.rejects(sendMessage("Вопрос", fetchImpl), error => {
    assert.equal(error.status, 500);
    assert.ok(!error.message.includes("private-internal-stack"));
    return true;
  });
});

test("network failure becomes an understandable transport error", async () => {
  await assert.rejects(sendMessage("Вопрос", async () => { throw new TypeError("fetch failed"); }), error => {
    assert.ok(error instanceof ChatApiError);
    assert.equal(error.status, 0);
    assert.match(error.message, /Нет связи/);
    return true;
  });
});

test("HTML error page is not used as a model answer", async () => {
  await assert.rejects(sendMessage("Вопрос", async () => new Response("<h1>Bad gateway</h1>", { status: 502 })),
    { name: "ChatApiError", status: 502 });
});

for (const payload of [{}, { answer: "" }, { answer: " " }, { answer: 42 }, null]) {
  test(`rejects invalid successful JSON payload ${JSON.stringify(payload)}`, async () => {
    await assert.rejects(sendMessage("Вопрос", async () => Response.json(payload)),
      { name: "ChatApiError", status: 200 });
  });
}
