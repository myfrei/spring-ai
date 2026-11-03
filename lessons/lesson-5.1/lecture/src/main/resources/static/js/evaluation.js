export function createEvaluationApi(fetchImpl = globalThis.fetch) {
  async function request(path, method = "GET", body) {
    let response;
    try { response = await fetchImpl(`/api/evaluation${path}`, { method, headers: { "Content-Type": "application/json", Accept: "application/json" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) }); }
    catch { throw new Error("Нет связи с приложением. Проверьте соединение."); }
    let data;
    try { data = await response.json(); } catch { throw new Error("Приложение вернуло неожиданный формат отчёта."); }
    if (!response.ok) throw new Error(data.detail || "Не удалось выполнить эксперимент.");
    return data;
  }
  return { start: body => request("/runs", "POST", body), get: id => request(`/runs/${encodeURIComponent(id)}`),
    cancel: id => request(`/runs/${encodeURIComponent(id)}/cancel`, "POST"), list: () => request("/runs"),
    judge: (id, ordinal, body) => request(`/runs/${encodeURIComponent(id)}/judgments/${ordinal}`, "PUT", body) };
}
const number = value => value == null ? "—" : Number(value).toFixed(1);
export function renderComparison(document, payload) {
  const { run, summary } = payload;
  document.getElementById("evaluation-status").textContent = `${run.state}: ${run.results.length} / ${run.planned}. `
    + `Warm-up: ${run.warmups.length}, исключён из сводки. ${run.message || ""}`;
  const conditions = run.conditions;
  document.getElementById("evaluation-conditions").textContent = conditions
    ? `${conditions.chat.model} · digest ${conditions.chat.digest.slice(0, 12)} · корпус ${conditions.corpusVersion.slice(0, 12)} · dataset ${conditions.datasetVersion.slice(0, 12)}. `
      + `Входной бюджет ${conditions.inputBudget}. Cache: ${conditions.cacheEvidence}. История для каждого вопроса пустая.` : "Проверяю модель и индекс…";
  const body = document.getElementById("evaluation-summary"); body.replaceChildren();
  for (const row of summary) {
    const tr = document.createElement("tr");
    for (const value of [row.mode, row.completed, row.noEvidence, row.failed, row.judged,
      number(row.meanAnswerScore), number(row.totalMs.median), number(row.endToEndTtftMs.median), row.endToEndTtftMs.n]) {
      const td = document.createElement("td"); td.textContent = String(value); tr.append(td);
    }
    body.append(tr);
  }
  const results = document.getElementById("evaluation-results"); results.replaceChildren();
  for (const row of run.results) {
    const item = document.createElement("li"); const details = document.createElement("details"); const title = document.createElement("summary");
    title.textContent = `#${row.ordinal} · ${row.caseId} · ${row.mode} · повтор ${row.repetition} · ${row.outcome} ${row.code}`;
    const question = document.createElement("pre"); question.textContent = row.question;
    const answer = document.createElement("pre"); answer.textContent = row.answer || "Ответа нет.";
    const metrics = document.createElement("p"); metrics.textContent = `Total ${number(row.timing.totalMs)} мс; поиск ${number(row.timing.retrievalMs)} мс; `
      + `TTFT модели ${number(row.timing.modelTtftMs)} мс; оценка входа ${row.estimatedInputTokens ?? "—"}; provider input ${row.providerInputTokens ?? "—"}. `
      + `Ожидаемое свидетельство в prompt: ${row.providedEvidenceHit ?? "не применимо"}. Это проверка размеченных фрагментов, не оценка смысла ответа.`;
    const sources = document.createElement("div");
    for (const source of row.sources) { const text = document.createElement("pre"); text.textContent = `[${source.label}] ${source.path}\n${source.text}`; sources.append(text); }
    const judgment = run.judgments?.[row.ordinal]; const review = document.createElement("p");
    review.textContent = judgment ? `Ручная оценка: ответ ${judgment.answer}/2, источники ${judgment.citations}/2. ${judgment.notes}` : "Ручная оценка не выполнена.";
    details.append(title, question, answer, metrics, sources, review); item.append(details); results.append(item);
  }
  const link = document.getElementById("evaluation-export"); link.hidden = false;
  link.href = `/api/evaluation/runs/${encodeURIComponent(run.id)}/report.md`; link.textContent = "Скачать Markdown-отчёт";
  const select = document.getElementById("evaluation-row"); const previous = select.value; select.replaceChildren();
  for (const row of run.results.filter(row => ["COMPLETED", "NO_EVIDENCE"].includes(row.outcome))) {
    const option = document.createElement("option"); option.value = String(row.ordinal); option.textContent = `#${row.ordinal} · ${row.caseId} · ${row.mode}`; select.append(option);
  }
  if ([...select.options].some(option => option.value === previous)) select.value = previous;
  document.getElementById("evaluation-review").disabled = run.state === "RUNNING" || select.options.length === 0;
}

export function mountEvaluation(document, { api = createEvaluationApi(), schedule = (callback, ms) => setTimeout(callback, ms), unschedule = id => clearTimeout(id) } = {}) {
  let revision = 0; let selected = null; let timer = null; let disposed = false; let requesting = false; let running = false;
  const error = document.getElementById("evaluation-error");
  const notice = document.getElementById("evaluation-notice");
  function lock() {
    document.getElementById("evaluation-controls").disabled = requesting || running;
    document.getElementById("evaluation-cancel").disabled = !running || requesting;
  }
  async function refresh() {
    if (!selected || disposed) return false;
    const id = selected;
    try {
      const payload = await api.get(id); if (disposed || selected !== id) return false;
      renderComparison(document, payload); running = payload.run.state === "RUNNING";
      if (running) timer = schedule(refresh, 1000);
      lock(); return true;
    } catch (failure) { error.textContent = failure.message; running = false; lock(); return false; }
  }
  async function start() {
    if (requesting || running || disposed) return false;
    revision++; requesting = true; error.textContent = ""; notice.textContent = ""; lock();
    try {
      const split = document.getElementById("evaluation-split").value;
      const rawIds = document.getElementById("evaluation-ids").value.trim();
      const run = await api.start({ split, caseIds: rawIds ? rawIds.split(",").map(value => value.trim()) : [],
        modes: ["CHAT", "FULL_CONTEXT", "RAG"], repeats: Number(document.getElementById("evaluation-repeats").value),
        topK: Number(document.getElementById("evaluation-topk").value), threshold: Number(document.getElementById("evaluation-threshold").value),
        warmup: document.getElementById("evaluation-warmup").checked });
      selected = run.id; running = true; await refresh(); return true;
    } catch (failure) { error.textContent = failure.message; return false; }
    finally { requesting = false; lock(); }
  }
  async function cancel() {
    if (!selected || !running || requesting) return false;
    requesting = true; lock();
    try { await api.cancel(selected); notice.textContent = "Запрошена остановка. Уже полученные результаты будут сохранены."; return true; }
    catch (failure) { error.textContent = failure.message; return false; }
    finally { requesting = false; lock(); }
  }
  async function review(event) {
    event?.preventDefault(); if (!selected || running || requesting) return false;
    requesting = true; lock();
    try {
      const answer = document.getElementById("evaluation-answer-score").value;
      const citations = document.getElementById("evaluation-citation-score").value;
      if (answer === "" || citations === "") throw new Error("Выберите обе оценки после чтения ответа и источников.");
      const refusal = document.getElementById("evaluation-refusal").value;
      await api.judge(selected, Number(document.getElementById("evaluation-row").value), { answer: Number(answer), citations: Number(citations),
        refusalCorrect: refusal === "" ? null : refusal === "true", notes: document.getElementById("evaluation-notes").value, reviewedAt: "" });
      await refresh(); error.textContent = ""; notice.textContent = "Оценка сохранена."; return true;
    } catch (failure) { error.textContent = failure.message; return false; }
    finally { requesting = false; lock(); }
  }
  async function restore() {
    const startedRevision = revision;
    try { const runs = await api.list(); if (runs.length && !disposed && revision === startedRevision && !requesting && !running) { selected = runs[0].id; await refresh(); } }
    catch (failure) { error.textContent = failure.message; }
  }
  document.getElementById("evaluation-start").addEventListener("click", start);
  document.getElementById("evaluation-cancel").addEventListener("click", cancel);
  document.getElementById("evaluation-review-form").addEventListener("submit", review);
  document.getElementById("evaluation-split").addEventListener("change", () => { document.getElementById("evaluation-ids").value = ""; });
  document.getElementById("evaluation-row").addEventListener("change", () => {
    for (const id of ["evaluation-answer-score", "evaluation-citation-score", "evaluation-refusal", "evaluation-notes"]) document.getElementById(id).value = "";
  });
  const ready = restore(); lock();
  return { ready, start, cancel, refresh, review, dispose() { disposed = true; if (timer != null) unschedule(timer); } };
}
