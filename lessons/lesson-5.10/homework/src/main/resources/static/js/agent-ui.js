export function createAgentPage(doc, api, options = {}) {
  const byId = id => doc.getElementById(id);
  const uuid = options.uuid || (()=>globalThis.crypto.randomUUID());
  const remember = options.remember || (()=>{});
  const statuses = {QUEUED:'Ожидает запуска',RUNNING:'Выполняется',COMPLETED:'Исследование завершено',STOPPED:'Остановлено',FAILED:'Не удалось выполнить'};
  const verification = {UNVERIFIED:'Проверка текущей версии не подтверждена.',CHECKED_PASS:'Фиксированный контракт текущей версии прошёл.',CHECKED_FAIL:'Фиксированный контракт текущей версии не прошёл.',STALE:'Сохранённая проверка относится к другой версии.'};
  let conversation=options.conversationId || null, job=null, cursor=0, controller=null, generation=0, pending=null, review=null;
  const error = e => { byId('agent-error').textContent = `${e.message}${e.code ? ` (${e.code})`:''}`; };
  const active = () => job && ['QUEUED','RUNNING'].includes(job.status);
  function render(value) {
    job=value;conversation=value.conversationId;remember(value.id);
    byId('agent-status').textContent=`${statuses[value.status] || value.status}${value.stopReason ? ` · ${value.stopReason}`:''}`;
    byId('agent-job-id').textContent=`Сессия: ${value.id}${value.runId ? ` · запуск: ${value.runId}`:''}`;
    byId('agent-answer').textContent=value.result?.run?.answer || '';
    byId('agent-verification').textContent=(verification[value.verification?.state] || verification.UNVERIFIED)+(value.verification?.reportId ? ` Отчёт: ${value.verification.reportId}`:'');
    byId('agent-submit').disabled=!!active();byId('agent-submit').textContent='Запустить задачу';byId('agent-cancel').disabled=!active();
    for(const name of ['agent-connect','agent-disconnect','agent-refresh'])byId(name).disabled=false;byId('agent-request-check').disabled=!!active();
    if(review){const current=value.approvals.find(a=>a.kind===review.kind && a.id===review.id);const allowed=current?.status==='PENDING' && !active();byId('agent-approve').disabled=!allowed;byId('agent-reject').disabled=!allowed;}
    byId('agent-plan').replaceChildren();
    for(const step of value.plan?.steps || []) { const item=doc.createElement('li');item.textContent=`${step.title}: ${step.status}${step.status==='COMPLETED' && !step.evidenceCurrent ? ' · версия изменилась':''}`;byId('agent-plan').append(item); }
    byId('agent-approvals').replaceChildren();
    for(const approval of value.approvals || []) { const item=doc.createElement('li'); const label=doc.createElement('span');label.textContent=`${approval.summary} · ${approval.status} `;const show=doc.createElement('button');show.type='button';show.textContent='Просмотреть';show.addEventListener('click',()=>inspect(approval).catch(error));item.append(label,show);byId('agent-approvals').append(item); }
    byId('agent-active-skills').textContent=`Применённые навыки: ${(value.result?.activations || []).map(s=>`${s.name} (${s.source})`).join(', ') || 'ещё не подтверждены'}`;
  }
  function disconnect() { generation++;if(controller)controller.abort();controller=null;byId('agent-connection').textContent='Обновления отключены. Это не отменяет задачу.'; }
  async function refresh() { if(job)render(await api.snapshot(job.id)); }
  async function watch(id) {
    disconnect();const current=++generation;controller=new AbortController();const signal=controller.signal;let failures=0;
    byId('agent-connection').textContent='Получаю события выполнения…';
    while(current===generation && !signal.aborted) {
      try {
        const page=await api.events(id,cursor,10000,signal);if(current!==generation)return;
        for(const event of page.events) { if(event.sequence<=cursor)continue;const item=doc.createElement('li');item.textContent=`${event.sequence}. ${event.type}${event.attributes?.name ? ` · ${event.attributes.name}`:''}`;byId('agent-events').append(item);cursor=event.sequence; }
        cursor=page.nextSequence;const value=await api.snapshot(id,signal);if(current!==generation)return;render(value);failures=0;
        if(page.terminal) { byId('agent-connection').textContent='Последние события получены.';return; }
      } catch(e) {
        if(signal.aborted || current!==generation || e.name==='AbortError')return;
        if(e.code==='SESSION_EVENTS_EXPIRED') { const value=await api.snapshot(id,signal);if(current!==generation)return;render(value);cursor=value.lastSequence;byId('agent-events').replaceChildren();byId('agent-connection').textContent='Загружено актуальное состояние; старая лента уже недоступна.';continue; }
        failures++;if(failures>=3){byId('agent-connection').textContent='Соединение прервано. Задача продолжает выполняться; нажмите «Подключиться».';return;}
        await new Promise(resolve=>setTimeout(resolve,100*failures));
      }
    }
  }
  async function attach(id) { disconnect();const token=generation;job=null;cursor=0;byId('agent-events').replaceChildren();const value=await api.snapshot(id);if(token!==generation)return;render(value);await watch(id); }
  function task() {
    const path=byId('agent-path').value.trim();const start=Number(byId('agent-start').value),end=Number(byId('agent-end').value);const question=byId('agent-question').value.trim();
    if(!question || !Number.isInteger(start) || !Number.isInteger(end) || start<1 || end<start || end-start>=80)throw new Error('Укажите задачу и диапазон не больше 80 строк.');
    return {question,mode:byId('agent-mode').value,maxInputTokens:16000,steps:[{id:'code',title:'Исследовать выбранный код',criterion:path.startsWith('src/test/')?'READ_TEST':'READ_CODE',path,startLine:start,endLine:end,dependsOn:[],required:true}]};
  }
  async function submit() {
    byId('agent-error').textContent='';const value=task();const hash=JSON.stringify(value);if(!pending || pending.hash!==hash)pending={requestId:uuid(),task:value,hash};
    if(!conversation)conversation=(await api.createConversation()).id;
    byId('agent-submit').disabled=true;
    try { const result=await api.submit(conversation,pending.requestId,pending.task);pending=null;cursor=0;byId('agent-events').replaceChildren();render(result);void watch(result.id).catch(error);return result; }
    catch(e){byId('agent-submit').disabled=false;byId('agent-submit').textContent='Повторить отправку';throw e;}
  }
  async function command(type, approvalId) {if(!job)throw new Error('Сначала выберите сессию.');const value=await api.command(job.id,type,approvalId);render(value);byId('agent-review').hidden=true;review=null;return value;}
  async function inspect(approval) {
    const value=await api.approval(job.id,approval.kind,approval.id);review={...approval};byId('agent-review-title').textContent=approval.summary;
    byId('agent-review-detail').textContent=approval.kind==='PATCH' ? `${value.path}\nИсходная версия: ${value.baseHash}\nРезультат: ${value.resultHash}\n\n${value.diff}` : `Команда: ${value.command}\nВерсия: ${value.inputRevision}\nОбраз: ${value.imageId}\nДо: ${value.expiresAt}`;
    byId('agent-review').hidden=false;byId('agent-approve').disabled=value.status!=='PENDING'||!!active();byId('agent-reject').disabled=value.status!=='PENDING'||!!active();
  }
  async function decide(allow) {if(!review)return;byId('agent-approve').disabled=true;byId('agent-reject').disabled=true;try{await command(`${allow?'APPROVE':'REJECT'}_${review.kind}`,review.id);}catch(e){error(e);await refresh();}}
  async function skills(){const list=await api.skills();byId('agent-skills').replaceChildren();for(const skill of list){const item=doc.createElement('li');item.textContent=`${skill.name}: ${skill.description}`;byId('agent-skills').append(item);}}
  byId('agent-form').addEventListener('submit',event=>{event.preventDefault();submit().catch(error);});
  byId('agent-connect').addEventListener('click',()=>{if(job)watch(job.id).catch(error);});
  byId('agent-disconnect').addEventListener('click',disconnect);
  byId('agent-refresh').addEventListener('click',()=>refresh().catch(error));
  byId('agent-cancel').addEventListener('click',()=>command('CANCEL').catch(error));
  byId('agent-request-check').addEventListener('click',()=>command('REQUEST_VERIFICATION').catch(error));
  byId('agent-approve').addEventListener('click',()=>decide(true));byId('agent-reject').addEventListener('click',()=>decide(false));
  byId('agent-skills-refresh').addEventListener('click',()=>skills().catch(error));
  return {attach,submit,disconnect,refresh,command,skills,destroy:disconnect,state:()=>({job,conversation,cursor})};
}
