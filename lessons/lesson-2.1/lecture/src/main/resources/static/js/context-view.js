export function clearContext(document) {
  document.getElementById("context-summary").textContent = "Сравнивайте режимы в новых диалогах. Отчёт появится при отправке.";
  document.getElementById("context-sources").replaceChildren();
}

export function renderContext(document, report) {
  const corpus = report.corpusVersion ? `Корпус ${report.corpusVersion.slice(0, 12)}. ` : "Без документов. ";
  document.getElementById("context-summary").textContent = `${report.mode}. ${corpus}`
    + `Оценка входа: ${report.estimatedInputTokens} / ${report.inputBudget}. `
    + `Пар из окна: ${report.includedHistoryTurns}, отброшено: ${report.omittedHistoryTurns}. `
    + `Кеширование не измерялось. Приложенные документы не означают подтверждённые цитаты.`;
  const list = document.getElementById("context-sources");
  list.replaceChildren();
  for (const source of report.sources) {
    const item = document.createElement("li");
    const link = document.createElement("a");
    link.href = `/api/corpus/documents/${encodeURIComponent(source.id)}`;
    link.target = "_blank"; link.rel = "noopener";
    link.textContent = `${source.id}: ${source.title}`;
    item.append(link); list.append(item);
  }
}
