import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {JSDOM} from 'jsdom';
import {createAttentionInbox, loadAcknowledgements} from '../../main/resources/static/attention-inbox.mjs';
const conversation = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', session = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
async function page() {return new JSDOM(await readFile(new URL('../../main/resources/static/attention.html', import.meta.url), 'utf8'), {url: 'http://localhost/attention.html'});}
const response = body => ({ok: true, status: 200, json: async () => body});
test('inbox reads snapshots and posts only display limits and scoped acknowledgement data', async () => {
 const dom = await page(), calls = [], storage = dom.window.localStorage;
 storage.setItem(`repopilot.attention.v1.${conversation}`, JSON.stringify({version: 1, acknowledgedThrough: {[session]: 3, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc': 99}}));
 const fetchImpl = async (url, options) => {calls.push({url, options}); return calls.length === 1 ? response([{id: session, lastSequence: 5}]) : response({notices: [], omittedCount: 0, omittedBlockingCount: 0, allActionableVisible: true});};
 const inbox = createAttentionInbox({document: dom.window.document, fetchImpl, storage, initialConversationId: conversation}); await inbox.refresh();
 assert.equal(calls.length, 2); assert.equal(calls[0].options, undefined); assert.deepEqual(JSON.parse(calls[1].options.body), {maxItems: 12, acknowledgedThrough: {[session]: 3}}); assert.ok(!calls.some(c => /commands|decision/.test(c.url))); dom.window.close();
});
test('pending notices cannot be acknowledged and rendered metadata cannot inject HTML', async () => {
 const dom = await page(); let count = 0;
 const fetchImpl = async () => ++count === 1 ? response([{id: session, lastSequence: 5}]) : response({notices: [{kind: 'APPROVAL_REQUIRED', sessionId: session, sequence: 5, blocking: true, code: '<img src=x onerror=alert(1)>'}], omittedCount: 2, omittedBlockingCount: 2, allActionableVisible: false});
 await createAttentionInbox({document: dom.window.document, fetchImpl, storage: dom.window.localStorage, initialConversationId: conversation}).refresh();
 assert.equal(dom.window.document.querySelectorAll('#notices button').length, 0); assert.equal(dom.window.document.querySelectorAll('#notices img').length, 0); assert.match(dom.window.document.getElementById('notices').textContent, /<img/); assert.match(dom.window.document.getElementById('state').textContent, /Скрытых запросов разрешения: 2/); assert.equal(dom.window.document.querySelector('#notices a').getAttribute('href'), `/agent.html?sessionId=${session}`); dom.window.close();
});
test('versioned malformed storage is ignored and unavailable storage keeps local acknowledgement usable', async () => {
 assert.deepEqual(loadAcknowledgements({getItem: () => '{bad'}, conversation), {});
 assert.deepEqual(loadAcknowledgements({getItem: () => JSON.stringify({version: 99, acknowledgedThrough: {[session]: 3}})}, conversation), {});
 const dom = await page(); let count = 0; const bodies = [];
 const fetchImpl = async (url, options) => {count++; if (!options) return response([{id: session, lastSequence: 5}]); bodies.push(JSON.parse(options.body)); return response({notices: bodies.length === 1 ? [{kind: 'STOPPED', sessionId: session, sequence: 5, blocking: false, code: 'CONTEXT_LIMIT'}] : [], omittedCount: 0, omittedBlockingCount: 0, allActionableVisible: true});};
 const storage = {getItem: () => {throw Error('unavailable');}, setItem: () => {throw Error('unavailable');}};
 const inbox = createAttentionInbox({document: dom.window.document, fetchImpl, storage, initialConversationId: conversation}); await inbox.refresh(); dom.window.document.querySelector('#notices button').click();
 for(let i=0;i<30&&bodies.length<2;i++) await new Promise(resolve=>setTimeout(resolve,1));
 assert.deepEqual(bodies[1].acknowledgedThrough, {[session]: 5}); assert.match(dom.window.document.getElementById('storage-warning').textContent, /недоступно/); assert.ok(count >= 4); dom.window.close();
});
