export function renderRag(document, report) {
  document.getElementById("context-summary").textContent = `${report.status}. RAG · ${report.sources.length} фрагментов. `
    + `Оценка входа: ${report.estimatedInputTokens} / ${report.inputBudget}; пар истории: ${report.includedHistoryTurns}. `
    + `Отброшено фрагментов по бюджету: ${report.omittedChunks}. Score — сходство, не вероятность правильного ответа. `
    + (report.status === "NO_EVIDENCE" ? "Chat-модель не вызывалась. Уточните вопрос или параметры поиска."
      : "Источники переданы модели; точность её цитат проверяйте по тексту.");
  const list = document.getElementById("context-sources"); list.replaceChildren();
  for (const source of report.sources) {
    const item = document.createElement("li"); const link = document.createElement("a");
    link.href = `/api/rag/chunks/${encodeURIComponent(report.indexFingerprint)}/${encodeURIComponent(source.chunkId)}`;
    link.target = "_blank"; link.rel = "noopener";
    link.textContent = `[${source.label}] ${source.path}:${source.startLine}–${source.endLine} · score ${source.score.toFixed(3)}`;
    item.append(link); list.append(item);
  }
}

export function mountRag(document, fetchImpl = globalThis.fetch) {
  const button = document.getElementById("publish-rag");
  const status = document.getElementById("rag-status");
  const error = document.getElementById("rag-error");
  async function publish() {
    if (button.disabled) return false;
    button.disabled = true; error.textContent = "";
    try {
      const response = await fetchImpl("/api/rag/publish", { method: "POST", headers: { Accept: "application/json" } });
      const data = await response.json();
      if (!response.ok) throw new Error(data.detail || "Не удалось опубликовать индекс.");
      status.textContent = `${data.reused ? "Публикация переиспользована" : "Опубликовано"}: ${data.manifest.chunkCount} чанков; `
        + `workspace ${data.manifest.workspace}; индекс ${data.manifest.fingerprint.slice(0, 12)}. Выберите режим RAG в новом диалоге.`;
      return true;
    } catch (failure) { error.textContent = failure.message || "Нет связи с приложением."; return false; }
    finally { button.disabled = false; }
  }
  button.addEventListener("click", publish);
  return { publish };
}
