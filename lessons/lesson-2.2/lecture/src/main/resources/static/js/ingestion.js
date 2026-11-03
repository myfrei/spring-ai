export function createIngestionApi(fetchImpl = globalThis.fetch) {
  async function request(path, options = {}) {
    let response;
    try { response = await fetchImpl(`/api/ingestion${path}`, { ...options,
      headers: { "Content-Type": "application/json", "Accept": "application/json, application/problem+json" } }); }
    catch { throw new Error("Нет связи с приложением. Проверьте соединение."); }
    if (!response.ok) {
      let problem;
      try { problem = await response.json(); } catch { /* Use stable public fallback. */ }
      throw new Error(typeof problem?.detail === "string" ? problem.detail : "Не удалось обработать документы.");
    }
    return response.json();
  }
  return { status: () => request(""), preview: options => request("/preview", { method: "POST", body: JSON.stringify(options) }),
    index: options => request("/index", { method: "POST", body: JSON.stringify(options) }) };
}

export function renderChunks(document, preview) {
  const list = document.getElementById("chunk-list"); list.replaceChildren();
  for (const chunk of preview.chunks.slice(0, 20)) {
    const item = document.createElement("li");
    const details = document.createElement("details");
    const summary = document.createElement("summary");
    summary.textContent = `${chunk.path}:${chunk.startLine}–${chunk.endLine} · ${chunk.section} · ${chunk.id.slice(0, 10)}`;
    const text = document.createElement("pre"); text.textContent = chunk.text;
    const source = document.createElement("a"); source.textContent = "Открыть документ";
    source.href = `/api/corpus/documents/${encodeURIComponent(chunk.documentId)}`; source.target = "_blank"; source.rel = "noopener";
    details.append(summary, source, text); item.append(details); list.append(item);
  }
}

export function mountIngestion(document, api = createIngestionApi()) {
  const status = document.getElementById("ingestion-status");
  const error = document.getElementById("ingestion-error");
  let busy = false;
  function lock(value) {
    busy = value;
    document.getElementById("ingestion-controls").disabled = value;
  }
  function options() {
    return { strategy: document.getElementById("chunk-strategy").value,
      maxCodePoints: Number(document.getElementById("chunk-size").value),
      overlapCodePoints: Number(document.getElementById("chunk-overlap").value) };
  }
  async function run(action) {
    if (busy) return false;
    lock(true); error.textContent = "";
    try { await action(); return true; }
    catch (failure) { error.textContent = failure.message || "Операция не завершена."; return false; }
    finally { lock(false); }
  }
  function saved(result) {
    status.textContent = `${result.reused ? "Снимок переиспользован без пересчёта" : "Снимок сохранён"}: ${result.chunkCount} чанков; `
      + `${result.embedding.model}; ${result.embedding.dimensions} измерений; digest ${result.embedding.digest.slice(0, 12)}; `
      + `корпус ${result.corpusVersion.slice(0, 12)}. Это подготовка индекса, поиск ещё не подключён.`;
  }
  async function preview() {
    return run(async () => {
      const result = await api.preview(options()); renderChunks(document, result);
      status.textContent = `Предпросмотр: ${result.chunks.length} чанков; корпус ${result.corpusVersion.slice(0, 12)}. `
        + "Показаны первые 20. Embeddings не запрашивались.";
    });
  }
  async function index() { return run(async () => saved(await api.index(options()))); }
  document.getElementById("preview-chunks").addEventListener("click", preview);
  document.getElementById("build-index").addEventListener("click", index);
  const ready = run(async () => {
    const result = await api.status();
    if (result.indexed) {
      saved(result.saved);
      if (result.currentCorpusVersion !== result.saved.corpusVersion || result.configuredModel !== result.saved.embedding.model
          || result.configuredDimensions !== result.saved.embedding.dimensions) {
        status.textContent += " Текущий корпус или конфигурация отличаются: требуется перестроение.";
      }
      status.textContent += " Digest runtime сейчас не проверялся; повторная индексация проверит его.";
    } else status.textContent = "Сохранённого индекса ещё нет. Начните с предпросмотра чанков.";
  });
  return { ready, preview, index };
}
