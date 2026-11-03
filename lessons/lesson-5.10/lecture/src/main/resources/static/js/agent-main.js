import {createSessionApi} from './session-api.js';
import {createAgentPage} from './agent-ui.js';
const params=new URLSearchParams(location.search);
const page=createAgentPage(document,createSessionApi(),{conversationId:params.get('conversationId'),remember:id=>{const url=new URL(location.href);url.searchParams.set('sessionId',id);history.replaceState(null,'',url);}});
if(params.get('sessionId'))page.attach(params.get('sessionId')).catch(error=>{document.getElementById('agent-error').textContent=error.message;});
page.skills().catch(error=>{document.getElementById('agent-error').textContent=error.message;});
