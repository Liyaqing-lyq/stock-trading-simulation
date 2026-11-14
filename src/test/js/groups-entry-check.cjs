"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm"), assert = require("assert");
const root = process.argv[2] || path.join(__dirname, "../../main/resources/web");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");
// 执行真实身份入口与刷新函数；其它领域卡片与网络用桩隔离，群组实现完整加载。
function sourceFunction(name) {
  const start = app.search(new RegExp("^(?:async )?function " + name + "\\(", "m"));
  assert(start >= 0, name);
  const rest = app.slice(start), next = rest.slice(1).search(/^(?:async )?function /m);
  return next < 0 ? rest : rest.slice(0, next + 1);
}
const nodes = new Map();
function node(id) {
  if (!nodes.has(id)) nodes.set(id, { id, hidden: false, textContent: "", value: "", dataset: {},
    children: [], appendChild(child) { child.parentElement = this; this.children.push(child); },
    append(...children) { children.forEach(c => this.appendChild(c)); },
    replaceChildren() { this.children = []; }, addEventListener() {},
    setAttribute() {}, removeAttribute() {} });
  return nodes.get(id);
}
const panel = node("group-panel"); panel.dataset.panel = "groups"; panel.hidden = true;
node("admin").appendChild(panel);
const state = { mode: "admin", generation: 0, page: "overview", pageVersion: 0, personGroups: null };
const calls = []; let pendingGroup = null, pendingAction = null;
const context = { state, console, BigInt, Number, String, Date, $: node,
  document: { body: {dataset:{}}, querySelector: () => panel,
    querySelectorAll: s => s === "[data-panel]" ? [panel] : [],
    createElement: tag => node("new-" + tag + "-" + nodes.size), addEventListener() {} },
  nextGeneration: () => ++state.generation, isCurrent: g => state.generation === g,
  show: (id, value) => {node(id).hidden = !value;}, text: String,
  setStatus() {}, setMessage() {}, sessionLost() {},
  cell: (tr, value) => tr.children.push({textContent:String(value)}),
  fillTable: (id, rows, build) => { const e=node(id); e.rows=rows; e.children=[];
    for(const row of rows || []) {const tr={children:[],appendChild(c){this.children.push(c);}};build(tr,row);e.children.push(tr);} },
  request: async (method,url) => { calls.push(url);
    if (url === "/api/me/groups") return pendingGroup || {status:200,payload:{personId:node("person-name").textContent,
      enabled:true,position:"5",timeMillis:"0",groups:[{groupId:"mine",owner:true}],posts:[],invitations:[],feed:[]}};
    if (method === "POST") return pendingAction || {status:200,payload:{receipt:"SCHEDULED:test"}};
    return {status:200,payload:{personPartyId:node("person-name").textContent, tools:[],accounts:[],holdings:[],market:[],catalog:0}};
  }
};
for(const name of ["clearAdminDom","clearPersonDom","clearPersonalMedia","clearPersonalInterview",
 "clearHistoryDom","renderPersonalMedia","renderPersonalInterview","renderPersonalDialogue","renderPersonalEvidence",
 "renderPersonalSocial","renderPersonalEngineering"]) context[name]=()=>{};
vm.createContext(context);
for(const name of ["showMode","enterPerson","refreshPerson"]) vm.runInContext(sourceFunction(name),context);
vm.runInContext(fs.readFileSync(path.join(root,"groups.js"),"utf8"),context);
(async()=>{
 await context.enterPerson("alice");
 assert(calls.includes("/api/me/groups"),"从真实人物入口必须加载本人群，不依赖管理员当前页");
 assert.equal(panel.parentElement,node("person"),"本人群必须移入可见人物区域");
 assert.equal(panel.hidden,false,"人物群面板必须可见");
 assert.equal(node("groups-person").hidden,false);
 assert.equal(node("navigation").hidden,true,"不得放开管理员导航");
 assert.equal(node("groups-mine").rows[0].groupId,"mine");
 let release; pendingGroup=new Promise(r=>release=r);
 const late=context.loadGroups(state.generation,null);
 context.showMode("login");
 release({status:200,payload:{groups:[{groupId:"private-stale"}]}}); await late;
 assert.equal(state.personGroups,null); assert.equal(node("groups-mine").rows.length,0);
 pendingGroup=null; await context.enterPerson("bob");
 let actionRelease; pendingAction=new Promise(r=>actionRelease=r);
 const action=context.submitGroupAction({action:"JOIN",groupId:"open"});
 context.showMode("login");
 actionRelease({status:200,payload:{receipt:"private-alice-receipt"}});await action;
 assert.equal(node("groups-result").textContent,"","退出后迟到动作回执不得回填");
 console.log("GROUPS-ENTRY-OK: real entry, visible panel, identity clearing, late responses");
})().catch(e=>{console.error(e);process.exitCode=1;});
