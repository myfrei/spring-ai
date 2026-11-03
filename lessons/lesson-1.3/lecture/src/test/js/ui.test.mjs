import test from "node:test";
import assert from "node:assert/strict";
import { mountChat } from "../../main/resources/static/js/app.js";
import { page, enter, submit, tick, deferred, completeSend } from "./fixtures.mjs";

test("idle page has available input and hidden cancel/retry",t=>{
 const dom=page();t.after(()=>dom.window.close());mountChat(dom.window.document,completeSend);
 for(const id of ["cancel-button","retry-button"])assert.equal(dom.window.document.getElementById(id).hidden,true);
 assert.equal(dom.window.document.getElementById("message").disabled,false);
});
test("renders deltas before completion in one assistant bubble",async t=>{
 const dom=page();t.after(()=>dom.window.close());const pending=deferred();let options;
 mountChat(dom.window.document,(m,o)=>{options=o;return pending.promise;});enter(dom,"Вопрос");submit(dom);
 const doc=dom.window.document;assert.equal(doc.getElementById("send-button").disabled,true);
 assert.equal(doc.getElementById("cancel-button").hidden,false);
 options.onEvent({type:"delta",text:"Первая "});options.onEvent({type:"delta",text:"часть"});
 assert.equal(doc.querySelectorAll(".message-assistant").length,1);
 assert.equal(doc.querySelector(".message-assistant pre").textContent,"Первая часть");
 assert.equal(doc.getElementById("status").textContent,"Пишу ответ…");
 pending.resolve();await tick();assert.equal(doc.getElementById("status").textContent,"Ответ завершён");
 assert.equal(doc.getElementById("message").value,"");
});
test("cancel preserves partial text and question while unlocking",async t=>{
 const dom=page();t.after(()=>dom.window.close());const pending=deferred();let options;
 mountChat(dom.window.document,(m,o)=>{options=o;return pending.promise;});enter(dom,"Вопрос");submit(dom);
 options.onEvent({type:"delta",text:"Часть"});const doc=dom.window.document;doc.getElementById("cancel-button").click();
 assert.equal(options.signal.aborted,true);assert.equal(doc.getElementById("status").textContent,"Остановлено");
 assert.equal(doc.querySelector(".message-assistant pre").textContent,"Часть");
 assert.equal(doc.getElementById("message").value,"Вопрос");assert.equal(doc.getElementById("send-button").disabled,false);
 assert.equal(doc.getElementById("retry-button").hidden,false);pending.resolve();await tick();
});
test("error marks partial response as interrupted",async t=>{
 const dom=page();t.after(()=>dom.window.close());mountChat(dom.window.document,async(m,{onEvent})=>{
  onEvent({type:"delta",text:"Часть"});throw new Error("Обрыв");
 });enter(dom,"Вопрос");submit(dom);await tick();const doc=dom.window.document;
 assert.equal(doc.getElementById("status").textContent,"Ответ не завершён");
 assert.equal(doc.querySelector(".message-status").textContent,"Прерван");
 assert.equal(doc.getElementById("error-box").hidden,false);assert.equal(doc.getElementById("message").value,"Вопрос");
});
test("HTML delivered through separate deltas stays literal text",async t=>{
 const dom=page();t.after(()=>dom.window.close());mountChat(dom.window.document,async(m,{onEvent})=>{
  onEvent({type:"delta",text:'<img src=x onerror="alert(1)">'});onEvent({type:"delta",text:"\nif (a < b) {}"});
 });enter(dom,"Вопрос");submit(dom);await tick();const doc=dom.window.document;
 assert.equal(doc.querySelectorAll("#messages img, #messages script").length,0);
 assert.equal(doc.querySelector(".message-assistant pre").textContent,'<img src=x onerror="alert(1)">\nif (a < b) {}');
});
test("retry preserves a different draft and one user bubble",async t=>{
 const dom=page();t.after(()=>dom.window.close());let calls=0;
 mountChat(dom.window.document,async(m,o)=>{
  if(++calls===1){o.onEvent({type:"delta",text:"Часть"});throw new Error("Обрыв");}
  assert.equal(m,"Исходный");return completeSend(m,o);
 });enter(dom,"Исходный");submit(dom);await tick();enter(dom,"Другой черновик");
 const doc=dom.window.document;doc.getElementById("retry-button").click();await tick();
 assert.equal(doc.getElementById("message").value,"Другой черновик");
 assert.equal(doc.querySelectorAll(".message-user").length,1);assert.equal(doc.querySelectorAll(".message-assistant").length,2);
});
test("blank input does not open transport",async t=>{
 const dom=page();t.after(()=>dom.window.close());let calls=0;
 mountChat(dom.window.document,async()=>{calls++;});enter(dom," ");submit(dom);await tick();
 assert.equal(calls,0);assert.equal(dom.window.document.getElementById("retry-button").hidden,true);
});
