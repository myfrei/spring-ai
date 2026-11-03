export function createSessionApi(fetchImpl = globalThis.fetch) {
  async function call(path, options = {}) {
    const response = await fetchImpl(path, options);
    const body = await response.json();
    if (!response.ok) { const error = new Error(body.detail || body.message || 'Запрос не выполнен.'); error.code = body.code; error.status = response.status; throw error; }
    return body;
  }
  const post = (path, value) => call(path, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});
  return {
    createConversation:()=>post('/api/agent/conversations', {}),
    submit:(conversation, requestId, task)=>post(`/api/sessions/conversations/${encodeURIComponent(conversation)}/jobs`, {requestId, task}),
    snapshot:(id,signal)=>call(`/api/sessions/jobs/${encodeURIComponent(id)}`, {signal}),
    list:(conversation)=>call(`/api/sessions/conversations/${encodeURIComponent(conversation)}/jobs`),
    events:(id,after,waitMs,signal)=>call(`/api/sessions/jobs/${encodeURIComponent(id)}/events?after=${after}&waitMs=${waitMs}`, {signal}),
    command:(id,type,approvalId)=>post(`/api/sessions/jobs/${encodeURIComponent(id)}/commands`, approvalId ? {type,approvalId}:{type}),
    approval:(id,kind,approvalId)=>call(`/api/sessions/jobs/${encodeURIComponent(id)}/approvals/${encodeURIComponent(kind)}/${encodeURIComponent(approvalId)}`),
    skills:()=>call('/api/sessions/skills')
  };
}
