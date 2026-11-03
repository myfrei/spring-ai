export class ChatApiError extends Error {
  constructor(message, status = 0) {
    super(message);
    this.name = "ChatApiError";
    this.status = status;
  }
}

export async function sendMessage(message, fetchImpl = globalThis.fetch) {
  let response;
  try {
    response = await fetchImpl("/api/chat", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "application/json, application/problem+json"
      },
      body: JSON.stringify({ message })
    });
  } catch {
    throw new ChatApiError("Нет связи с приложением. Проверьте соединение и повторите запрос.");
  }

  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new ChatApiError("Сервер вернул неожиданный ответ. Попробуйте ещё раз.", response.status);
  }

  if (!response.ok) {
    const publicDetail = [400, 503].includes(response.status)
      && typeof payload?.detail === "string" && payload.detail.trim();
    throw new ChatApiError(publicDetail ? payload.detail
      : "Не удалось получить ответ. Попробуйте ещё раз.", response.status);
  }
  if (typeof payload?.answer !== "string" || !payload.answer.trim()) {
    throw new ChatApiError("Сервер вернул пустой или некорректный ответ.", response.status);
  }
  return payload.answer;
}
