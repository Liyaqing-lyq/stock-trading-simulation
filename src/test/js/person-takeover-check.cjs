"use strict";
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

const elements = new Map();
function get(id) {
  if (!elements.has(id)) elements.set(id,{textContent:"", hidden:true, disabled:false, querySelector:() => get("submit")});
  return elements.get(id);
}
const state = {mode:"person", generation:1, position:"42"};
const context = {state, $:get, URL, document:{documentElement:{classList:{toggle(){}}},addEventListener(){}},
  isCurrent:g => g === state.generation, history:{replaceState(){}}, location:{href:"http://127.0.0.1:18080/",
    hostname:"127.0.0.1",hash:"",assign(url){this.destination = url;}}};
const calls = [];
let response;
context.request = async (method, url, body) => {
  calls.push([method, url, JSON.parse(JSON.stringify(body || {}))]);
  return response;
};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,"../../main/resources/web/person-takeover.js"),"utf8"),context);
const ui = context.PersonTakeover;
(async () => {
  ui.person(null,"investor",null);
  assert.equal(get("takeover-actor").hidden,true);
  assert.equal(ui.blocks("POST","/api/me/read"),false);
  ui.person("READ_ONLY","investor","false");
  ui.cut("9007199254740995");
  await ui.start("investor","9007199254740995");
  assert.equal(calls.length,0);
  assert.equal(get("takeover-actor").hidden,true);
  assert.equal(ui.blocks("POST","/api/me/orders"),true);
  assert.equal(ui.blocks("POST","/api/me/view"),false);
  ui.person("READ_ONLY","investor","true");
  ui.cut("9007199254740995");
  assert.equal(get("takeover-actor").disabled,false);
  await ui.start("other-person","9007199254740995");
  assert.equal(calls.length,0);
  response={status:409,payload:{error:"POSITION_MISMATCH"}};
  await ui.start("investor","9007199254740995");
  assert.deepEqual(calls.pop(),["POST","/api/me/takeover",{expectedPosition:"9007199254740995"}]);
  assert.match(get("takeover-feedback").textContent,/位置已变化/);
  assert.equal(context.location.destination,undefined);
  response={status:200,payload:{location:"http://127.0.0.1:18081/"}};
  await ui.start("investor","9007199254740995");
  assert.equal(context.location.destination,"http://127.0.0.1:18081/");
  ui.person("TAKEOVER","investor","false");
  assert.equal(ui.blocks("POST","/api/me/orders"),false);
  assert.match(get("context-intervention").textContent,/GOD_TAKEOVER/);
  state.mode="admin";ui.reset();
  response={status:200,payload:{personActionMode:"READ_ONLY",launcher:true}};
  await ui.admin();
  assert.equal(get("takeover-selected").hidden,false);
  response={status:200,payload:{location:"http://127.0.0.1:18082/"}};
  await ui.start("investor","42");
  assert.deepEqual(calls.pop(),["POST","/api/admin/person-takeover",{expectedPosition:"42",personId:"investor"}]);
  assert.equal(context.location.destination,"http://127.0.0.1:18082/#takeover-person=investor");
  console.log("个人只读、两种接管授权、过期观察及旧档兼容均通过");
})().catch(error => {console.error(error);process.exitCode=1;});
