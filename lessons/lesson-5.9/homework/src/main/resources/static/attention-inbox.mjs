const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const safeSequence = n => Number.isSafeInteger(n) && n >= 0;
const storageKey = id => `repopilot.attention.v1.${id}`;
export function loadAcknowledgements(storage, id) {
  try {
    const data = JSON.parse(storage?.getItem(storageKey(id)) ?? 'null');
    if (data?.version !== 1 || !data.acknowledgedThrough || typeof data.acknowledgedThrough !== 'object' || Array.isArray(data.acknowledgedThrough)) return {};
    return Object.fromEntries(Object.entries(data.acknowledgedThrough).filter(([key, value]) => UUID.test(key) && safeSequence(value)).slice(0, 64));
  } catch { return {}; }
}
export function saveAcknowledgements(storage, id, values) {
  try { storage.setItem(storageKey(id), JSON.stringify({version: 1, acknowledgedThrough: values})); return true; }
  catch { return false; }
}
export function createAttentionInbox({document, fetchImpl, storage, initialConversationId = ''}) {
  const form = document.getElementById('attention-form'), input = document.getElementById('conversation-id');
  const limit = document.getElementById('max-items'), button = document.getElementById('refresh');
  const state = document.getElementById('state'), error = document.getElementById('error');
  const warning = document.getElementById('storage-warning'), list = document.getElementById('notices');
  input.value = UUID.test(initialConversationId) ? initialConversationId : '';
  let currentId = '', acknowledged = {}, generation = 0;
  async function json(url, options) {
    const response = await fetchImpl(url, options);
    const body = await response.json();
    if (!response.ok) throw new Error(`${response.status}: ${typeof body?.code === 'string' ? body.code : 'REQUEST_FAILED'}`);
    return body;
  }
  async function refresh() {
    const id = input.value.trim(), maxItems = Number(limit.value), token = ++generation;
    error.textContent = ''; list.replaceChildren();
    if (!UUID.test(id) || !Number.isInteger(maxItems) || maxItems < 1 || maxItems > 12) { error.textContent = 'Введите UUID диалога и лимит от 1 до 12.'; button.disabled = false; state.textContent = ''; return; }
    if (currentId !== id) { currentId = id; acknowledged = loadAcknowledgements(storage, id); }
    button.disabled = true; state.textContent = 'Загружаем серверные снимки…';
    try {
      const sessions = await json(`/api/sessions/conversations/${encodeURIComponent(id)}/jobs`);
      if (!Array.isArray(sessions) || sessions.length > 64) throw new Error('INVALID_SESSION_LIST');
      const high = new Map();
      for (const s of sessions) if (UUID.test(s?.id) && safeSequence(s.lastSequence)) high.set(s.id, Math.max(high.get(s.id) ?? 0, s.lastSequence));
      const valid = Object.fromEntries(Object.entries(acknowledged).filter(([key, seq]) => high.has(key) && seq <= high.get(key)));
      const report = await json(`/api/sessions/conversations/${encodeURIComponent(id)}/attention/check`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({maxItems, acknowledgedThrough: valid})});
      if (token !== generation) return;
      if (!Array.isArray(report?.notices) || report.notices.length > 12) throw new Error('INVALID_ATTENTION_REPORT');
      acknowledged = valid;
      state.textContent = `В списке: ${report.notices.length}. Осталось за лимитом: ${report.omittedCount ?? 0}. Скрытых запросов разрешения: ${report.omittedBlockingCount ?? 0}.`;
      if (report.allActionableVisible === false) state.textContent += ' Откройте видимые разрешения в сессиях, затем обновите список.';
      if (!report.notices.length) state.textContent = 'Сейчас нет непросмотренных уведомлений или ожидающих разрешений.';
      for (const notice of report.notices) {
        const row = document.createElement('li'), title = document.createElement('strong'), detail = document.createElement('p');
        title.textContent = notice.kind === 'APPROVAL_REQUIRED' ? 'Нужно решение человека' : notice.kind === 'FAILED' ? 'Сессия завершилась ошибкой' : 'Сессия остановлена';
        detail.textContent = `${notice.approvalKind ?? ''} ${notice.approvalId ?? ''} ${notice.code ?? ''}`.trim();
        row.append(title, detail);
        if (UUID.test(notice.sessionId)) {
          const link = document.createElement('a'); link.href = `/agent.html?sessionId=${encodeURIComponent(notice.sessionId)}`; link.textContent = 'Открыть сессию'; row.append(link);
        }
        if (notice.blocking !== true && UUID.test(notice.sessionId) && safeSequence(notice.sequence)) {
          const ack = document.createElement('button'); ack.type = 'button'; ack.textContent = 'Просмотрено';
          ack.addEventListener('click', () => {
            if (input.value.trim() !== id) { error.textContent = 'Сначала обновите список выбранного диалога.'; return; }
            acknowledged[notice.sessionId] = Math.max(acknowledged[notice.sessionId] ?? 0, notice.sequence);
            if (!saveAcknowledgements(storage, id, acknowledged)) warning.textContent = 'Хранилище браузера недоступно: отметки сохранятся только до перезагрузки страницы.';
            refresh();
          }); row.append(ack);
        }
        list.append(row);
      }
    } catch (failure) { if (token === generation) { state.textContent = ''; error.textContent = failure instanceof Error ? failure.message : 'Не удалось загрузить список.'; } }
    finally { if (token === generation) button.disabled = false; }
  }
  form.addEventListener('submit', event => { event.preventDefault(); refresh(); });
  return {refresh};
}
