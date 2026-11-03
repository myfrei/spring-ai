import test from "node:test";
import assert from "node:assert/strict";
import { createChatController } from "../../main/resources/static/js/chat-controller.js";
import { completeSend, deferred } from "./fixtures.mjs";

test("publishes idle state",()=>{
 const states=[];const c=createChatController({send:completeSend,onStateChange:s=>states.push(s)});
 assert.deepEqual(c.getState(),{phase:"idle",messages:[],error:"",retryMessage:null});assert.equal(states.length,1);
});
test("invalid input never opens transport",async()=>{
 let calls=0;const c=createChatController({send:async()=>{calls++;}});
 for(const value of [""," ","x".repeat(4001)])assert.equal(await c.submit(value),false);
 assert.equal(calls,0);assert.equal(c.getState().messages.length,0);
});
test("deltas become visible before completion",async()=>{
 const pending=deferred();let options;const c=createChatController({send:(m,o)=>{options=o;return pending.promise;}});
 const run=c.submit(" Вопрос ");assert.equal(c.getState().phase,"connecting");
 options.onEvent({type:"started"});options.onEvent({type:"delta",text:"Первая"});
 assert.equal(c.getState().phase,"streaming");assert.equal(c.getState().messages[1].content,"Первая");
 pending.resolve();assert.equal(await run,true);assert.equal(c.getState().messages[1].status,"complete");
});
test("failure keeps partial text marked interrupted",async()=>{
 const c=createChatController({send:async(m,{onEvent})=>{onEvent({type:"delta",text:"Часть"});throw new Error("Обрыв");}});
 assert.equal(await c.submit("Вопрос"),false);assert.equal(c.getState().messages[1].content,"Часть");
 assert.equal(c.getState().messages[1].status,"interrupted");assert.equal(c.getState().retryMessage,"Вопрос");
});
test("cancel aborts and unlocks immediately, late events cannot change partial answer",async()=>{
 const pending=deferred();let options;const c=createChatController({send:(m,o)=>{options=o;return pending.promise;}});
 const run=c.submit("Вопрос");options.onEvent({type:"delta",text:"Часть"});assert.equal(c.cancel(),true);
 assert.equal(options.signal.aborted,true);assert.equal(c.getState().phase,"cancelled");
 options.onEvent({type:"delta",text:"ЛИШНИЙ"});pending.resolve();assert.equal(await run,false);
 assert.equal(c.getState().messages[1].content,"Часть");assert.equal(c.getState().messages[1].status,"cancelled");
});
test("late old callbacks cannot overwrite a new successful request",async()=>{
 const pending=deferred();let old;let calls=0;const c=createChatController({send:(m,o)=>{
  if(++calls===1){old=o;return pending.promise;}return completeSend(m,o);
 }});
 const first=c.submit("Старый");c.cancel();assert.equal(await c.submit("Новый"),true);
 old.onEvent({type:"delta",text:"Чужое"});pending.reject(new Error("late"));await first;
 assert.equal(c.getState().phase,"success");assert.equal(c.getState().messages.at(-1).content,"Ответ");
});
test("busy blocks duplicate submit and retry",async()=>{
 const pending=deferred();let calls=0;const c=createChatController({send:()=>{calls++;return pending.promise;}});
 const first=c.submit("Вопрос");assert.equal(await c.submit("Ещё"),false);assert.equal(await c.retry(),false);
 assert.equal(calls,1);c.cancel();pending.resolve();await first;
});
test("retry keeps old partial attempt and creates a new persisted turn",async()=>{
 let calls=0;const c=createChatController({send:async(m,o)=>{
  if(++calls===1){o.onEvent({type:"delta",text:"Старая часть"});throw new Error("Обрыв");}
  assert.equal(m,"Вопрос");return completeSend(m,o);
 }});
 await c.submit("Вопрос");assert.equal(await c.retry(),true);const messages=c.getState().messages;
 assert.equal(messages.filter(m=>m.role==="user").length,2);
 assert.equal(messages[1].content,"Старая часть");assert.equal(messages[1].status,"interrupted");
 assert.equal(messages[3].content,"Ответ");assert.equal(messages[3].status,"complete");
});
test("retry after cancellation uses original question",async()=>{
 const pending=deferred();let calls=0;const c=createChatController({send:(m,o)=>{
  if(++calls===1)return pending.promise;assert.equal(m,"Исходный");return completeSend(m,o);
 }});
 const first=c.submit(" Исходный ");c.cancel();assert.equal(await c.retry(),true);pending.resolve();await first;
 assert.equal(c.getState().messages.filter(m=>m.role==="user").length,2);
});
test("retry is unavailable initially and validation clears candidate",async()=>{
 const c=createChatController({send:completeSend});assert.equal(await c.retry(),false);
 await c.submit("Вопрос");assert.equal(c.cancel(),false);await c.submit(" ");assert.equal(c.getState().retryMessage,null);
});
test("empty resolved transport is never marked complete",async()=>{
 const c=createChatController({send:async()=>{}});assert.equal(await c.submit("Вопрос"),false);
 assert.equal(c.getState().messages[1].status,"interrupted");
});
