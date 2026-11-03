export function createDiagnosticApi(fetchImpl = globalThis.fetch) {
  async function request(path, method = "GET") {
    let response;
    try { response = await fetchImpl(`/api/diagnostics${path}`, { method, headers: { Accept: "application/json" } }); }
    catch { throw new Error("Нет связи с приложением."); }
    let data; try { data = await response.json(); } catch { throw new Error("Некорректный формат диагностики."); }
    if (!response.ok) throw new Error(data.detail || "Не удалось получить диагностику.");
    return data;
  }
  return { latest: () => request("/runs/latest"), lab: () => request("/lab", "POST"), trace: id => request(`/traces/${encodeURIComponent(id)}`) };
}
export function renderDiagnostics(document, report, onTrace = () => {}) {
  document.getElementById("diagnostic-status").textContent = `${report.origin === "SYNTHETIC_LAB" ? "ТРЕНАЖЁР · fixtures, без вызова LLM" : "Эксперимент"}. `
    + `Run ${report.runId}. Правила ${report.rulesVersion}. Совместимая разметка: ${report.baselineCompatible}. ${report.note}`;
  const list = document.getElementById("diagnostic-findings"); list.replaceChildren();
  document.getElementById("diagnostic-trace").replaceChildren();
  for (const row of report.rows) {
    const item = document.createElement("li"); const title = document.createElement("h3");
    title.textContent = `#${row.ordinal} · ${row.caseId} · ${row.mode}: ${row.finding.label}`;
    const facts = document.createElement("ul");
    for (const fact of row.finding.facts) { const line = document.createElement("li"); line.textContent = fact; facts.append(line); }
    const hypothesis = document.createElement("p"); hypothesis.textContent = `Гипотеза: ${row.finding.hypothesis}`;
    const experiment = document.createElement("p"); experiment.textContent = `Проверить: ${row.finding.nextExperiment}`;
    const button = document.createElement("button"); button.type = "button"; button.textContent = "Открыть trace";
    button.disabled = !row.traceAvailable || !/^[0-9a-f-]{36}$/.test(row.traceId);
    button.addEventListener("click", () => onTrace(row.traceId));
    const warning = document.createElement("p"); warning.textContent = row.traceWarning ? `Trace: ${row.traceWarning}. Для старого run может потребоваться новый прогон.` : `traceId=${row.traceId}`;
    item.append(title, facts, hypothesis, experiment, button, warning);
    if (row.sampleAnswer) { const sample = document.createElement("pre"); sample.textContent = `Fixture-вопрос: ${row.sampleQuestion}\nFixture-ответ: ${row.sampleAnswer}`; item.append(sample); }
    list.append(item);
  }
  const link = document.getElementById("diagnostic-export"); link.hidden = report.origin !== "EXPERIMENT";
  if (!link.hidden) link.href = `/api/diagnostics/runs/${encodeURIComponent(report.runId)}/report.md`;
}
export function renderTrace(document, trace) {
  const container = document.getElementById("diagnostic-trace"); container.replaceChildren();
  const title = document.createElement("h3"); title.textContent = `Trace ${trace.id} · ${trace.outcome}${trace.context.synthetic ? " · SYNTHETIC" : ""}`;
  const policy = document.createElement("p"); policy.textContent = "Метаданные стадий. Тексты вопроса, документов и ответа в trace не сохраняются.";
  const list = document.createElement("ol");
  for (const event of trace.events) {
    const item = document.createElement("li"); item.textContent = `${event.sequence}. ${event.stage} · ${event.status} · +${event.elapsedMs.toFixed(1)} мс · ${event.code} · ${JSON.stringify(event.counts)}`; list.append(item);
  }
  const markers = document.createElement("pre"); markers.textContent = JSON.stringify(trace.sources, null, 2);
  container.append(title, policy, list, markers);
}
export function mountDiagnostics(document, api = createDiagnosticApi()) {
  let busy = false; let revision = 0;
  const error = document.getElementById("diagnostic-error");
  function lock(value) { busy = value; for (const id of ["diagnostic-latest", "diagnostic-lab"]) document.getElementById(id).disabled = value; }
  async function inspect(id) {
    const version = revision;
    try { const trace = await api.trace(id); if (version === revision) renderTrace(document, trace); }
    catch (failure) { if (version === revision) error.textContent = failure.message; }
  }
  async function load(action) {
    if (busy) return false;
    lock(true); revision++; error.textContent = "";
    try { renderDiagnostics(document, await action(), inspect); return true; }
    catch (failure) { error.textContent = failure.message; return false; }
    finally { lock(false); }
  }
  const lab = () => load(api.lab); const latest = () => load(api.latest);
  document.getElementById("diagnostic-lab").addEventListener("click", lab);
  document.getElementById("diagnostic-latest").addEventListener("click", latest);
  return { lab, latest, inspect };
}
