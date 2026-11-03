import { streamMessage } from "./stream-api.js";

export function createConversationApi(fetchImpl = globalThis.fetch) {
  async function json(path, options = {}) {
    const response = await fetchImpl(path, { ...options,
      headers: { "Accept": "application/json, application/problem+json", ...options.headers } });
    if (!response.ok) {
      let problem;
      try { problem = await response.json(); } catch { /* Use a public fallback. */ }
      throw new Error([400, 404, 409].includes(response.status) && problem?.detail
        ? problem.detail : "Не удалось загрузить диалоги. Проверьте приложение.");
    }
    return response.json();
  }
  const path = id => `/api/conversations/${encodeURIComponent(id)}`;
  return {
    list: () => json("/api/conversations"),
    create: title => json("/api/conversations", { method: "POST",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify({ title }) }),
    detail: id => json(path(id)),
    send: (id, message, options) => streamMessage(message,
      { ...options, url: `${path(id)}/context/stream` }, fetchImpl)
  };
}
