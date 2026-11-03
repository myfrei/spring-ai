import { createSseParser } from "./sse-parser.js";

export class ChatStreamError extends Error {
  constructor(message, status = 0, code = "STREAM_ERROR") {
    super(message); this.name = "ChatStreamError"; this.status = status; this.code = code;
  }
}

export async function streamMessage(message, { signal, onEvent = () => {} } = {}, fetchImpl = globalThis.fetch) {
  const aborted = () => new DOMException("Запрос остановлен.", "AbortError");
  if (signal?.aborted) throw aborted();
  let response;
  try {
    response = await fetchImpl("/api/chat/stream", {
      method: "POST", signal,
      headers: { "Content-Type": "application/json", "Accept": "text/event-stream, application/problem+json" },
      body: JSON.stringify({ message })
    });
  } catch {
    if (signal?.aborted) throw aborted();
    throw new ChatStreamError("Нет связи с приложением. Повторите запрос.", 0, "NETWORK");
  }
  if (!response.ok) {
    let payload;
    try { payload = await response.json(); } catch { /* Use the stable fallback. */ }
    if (signal?.aborted) throw aborted();
    const detail = [400, 503].includes(response.status) && typeof payload?.detail === "string" && payload.detail.trim();
    throw new ChatStreamError(detail ? payload.detail : "Не удалось начать поток ответа.", response.status, "HTTP_ERROR");
  }
  if (!response.headers.get("Content-Type")?.toLowerCase().startsWith("text/event-stream") || !response.body) {
    await response.body?.cancel();
    throw new ChatStreamError("Сервер вернул неожиданный формат потока.", response.status, "PROTOCOL_ERROR");
  }

  const parser = createSseParser();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  const reader = response.body.getReader();
  let started = false;
  let hasText = false;

  function consume(frame) {
    const event = JSON.parse(frame.data);
    if (!event || event.type !== frame.event || !["started", "delta", "completed", "failed"].includes(event.type)) {
      throw new Error("Invalid event");
    }
    if (event.type === "started") {
      if (started) throw new Error("Duplicate started");
      started = true;
    } else if (!started) throw new Error("Missing started");
    if (event.type === "delta") {
      if (typeof event.text !== "string") throw new Error("Invalid delta");
      hasText ||= Boolean(event.text.trim());
    }
    if (event.type === "completed" && !hasText) throw new Error("Empty answer");
    if (event.type === "failed" && (typeof event.message !== "string" || !event.message || typeof event.code !== "string")) {
      throw new Error("Invalid failure");
    }
    onEvent(event);
    if (event.type === "failed") throw new ChatStreamError(event.message, response.status, event.code);
    return event.type === "completed";
  }

  try {
    while (true) {
      let chunk;
      try { chunk = await reader.read(); }
      catch {
        if (signal?.aborted) throw aborted();
        throw new ChatStreamError("Соединение оборвалось. Полученный ответ не завершён.", 0, "CONNECTION_LOST");
      }
      if (signal?.aborted) throw aborted();
      if (chunk.done) break;
      for (const frame of parser.push(decoder.decode(chunk.value, { stream: true }))) {
        if (consume(frame)) return;
      }
    }
    for (const frame of [...parser.push(decoder.decode()), ...parser.finish()]) {
      if (consume(frame)) return;
    }
    throw new ChatStreamError("Поток закрылся без подтверждения завершения ответа.", 200, "INCOMPLETE_STREAM");
  } catch (error) {
    if (error instanceof ChatStreamError || error.name === "AbortError") throw error;
    throw new ChatStreamError("Получен некорректный или незавершённый поток ответа.", 200, "PROTOCOL_ERROR");
  } finally {
    try { await reader.cancel(); } catch { /* Connection may already be closed. */ }
    reader.releaseLock();
  }
}
