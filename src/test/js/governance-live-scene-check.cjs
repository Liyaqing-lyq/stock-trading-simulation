// 治理场景 Web 前端契约：只检查“本轮场景”选择的真实绑定、请求体字段与身份切换清空。
// 用法：node governance-live-scene-check.cjs <webDir>
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const webDir = process.argv[2] || path.join(__dirname, "../../main/resources/web");
const source = fs.readFileSync(path.join(webDir, "app.js"), "utf8");
const html = fs.readFileSync(path.join(webDir, "index.html"), "utf8");

function makeElement(tag = "div", registry) {
  const node = {
    tag, attrs: {}, children: [], _text: "", hidden: false, value: "", required: false,
    dataset: {}, htmlFor: "", type: "text", autocomplete: "", _id: "", listeners: {},
    get textContent() { return this._text; },
    set textContent(v) { this._text = v === null || v === undefined ? "" : String(v); },
    setAttribute(k, v) { this.attrs[k] = String(v); },
    getAttribute(k) { return this.attrs[k] === undefined ? null : this.attrs[k]; },
    removeAttribute(k) { delete this.attrs[k]; },
    appendChild(child) { this.children.push(child); return child; },
    append(...items) { this.children.push(...items); },
    replaceChildren(...items) { this.children = items; },
    addEventListener(type, handler) { (this.listeners[type] = this.listeners[type] || []).push(handler); },
    focus() { this.focused = true; },
    querySelectorAll() { return []; },
    querySelector() { return makeElement("queried", registry); },
    classList: { add() { }, remove() { }, toggle() { } }
  };
  Object.defineProperty(node, "id", {
    get() { return node._id; },
    set(value) {
      node._id = value;
      if (registry && value) registry.set(value, node);
    }
  });
  return node;
}

function makeDocument() {
  const nodes = new Map();
  const document = {
    body: makeElement("body", nodes),
    getElementById(id) {
      if (!nodes.has(id)) {
        const node = makeElement(id, nodes);
        node._id = id;
        nodes.set(id, node);
      }
      return nodes.get(id);
    },
    createElement(tag) { return makeElement(tag, nodes); },
    createElementNS(ns, tag) { return makeElement(tag, nodes); },
    createTextNode(v) { const n = makeElement("#text", nodes); n.textContent = v; return n; },
    querySelectorAll() { return []; },
    addEventListener() { }
  };
  return document;
}

const document = makeDocument();
const fetchCalls = [];
const context = vm.createContext({
  document, console, BigInt, Number, String, Object, Array, Map, Set, JSON,
  setTimeout, clearTimeout, setInterval, clearInterval,
  fetch(url, options) {
    fetchCalls.push({ url, options });
    return new Promise(() => { });
  }
});
vm.runInContext(source, context);
vm.runInContext("globalThis.__api = { state, liveCheckedPersons, livePersonScenes, liveStartBody,"
  + " renderLiveSceneChoices, renderLiveCompanyChoices, liveRoundSummary, clearAdminDom,"
  + " clearLiveDom, renderLivePersons, livePersonPool, LIVE_SCENES } ;", context);
const api = context.__api;

function fire(node, type) {
  const handlers = (node && node.listeners && node.listeners[type]) || [];
  assert.ok(handlers.length > 0, "元素没有登记 " + type + " 处理器");
  for (const handler of handlers) handler({ preventDefault() { } });
}

/** 选项列表（value 数组），用于断言“只出现真正可用的场景/公司”。 */
function optionValues(select) {
  return select.children.map(child => child.value);
}

/** 模拟真实勾选：document.querySelectorAll 返回已勾选的人物输入。 */
function selectPersons(persons) {
  document.querySelectorAll = selector =>
    (String(selector).includes('name="live-person"') ? persons.map(value => ({ value })) : []);
}

const persons = {
  alice: { personPartyId: "alice", name: "甲", sceneId: "research",
    scenes: ["research", "governance"], governanceCompanies: ["cufe"] },
  dave: { personPartyId: "dave", name: "戴夫", sceneId: "trading",
    scenes: ["trading", "governance"], governanceCompanies: ["cufe", "eng-a"] },
  omar: { personPartyId: "omar", name: "奥马尔", sceneId: "media",
    scenes: ["media"], governanceCompanies: [] },
  erin: { personPartyId: "erin", name: "戊", sceneId: null,
    scenes: ["governance"], governanceCompanies: ["cufe"] }
};
const capabilities = {
  position: "7", timeMillis: "1000", mediaAvailable: true,
  supportedScenes: ["research", "trading", "media", "governance"],
  persons: [persons.alice, persons.dave, persons.omar],
  governancePersons: [persons.alice, persons.dave, persons.erin]
};

function adminLive() {
  api.state.mode = "admin";
  api.state.page = "models";
  api.state.generation += 1;
  api.state.capabilities = capabilities;
  api.state.routeRevision = "5";
  api.state.live = { phase: "IDLE", position: "7", paused: false, fault: null,
    physicalInFlight: 0, completionSlots: 0, pendingPersons: [], decisions: [],
    skips: [], phones: [], scenes: [], personBudgets: [], runBudget: null,
    roundPersons: [], roundScene: null, roundCompany: null };
  api.state.liveBusy = false;
}

const cases = [];
function check(name, run) {
  try { run(); cases.push(["PASS", name]); }
  catch (failure) { cases.push(["FAIL", name + " :: " + failure.message]); }
}

// ---------------------------------------------------------------- 1 静态接线
check("html-has-scene-and-company-selects", () => {
  assert.ok(html.includes('id="live-scene"'), "缺本轮场景选择器");
  assert.ok(html.includes('id="live-company"'), "缺治理公司选择器");
  assert.ok(html.includes('id="live-round"'), "缺本轮摘要行");
  assert.ok(html.includes("公司治理"), "页面说明未提到公司治理");
});
check("governance-label-is-not-placeholder", () => {
  assert.equal(api.LIVE_SCENES.governance, "公司治理");
  assert.ok(api.LIVE_SCENES.evaluation.includes("未接入"), "评估场景必须仍标注未接入");
});

// ---------------------------------------------------------------- 2 选项来源
check("scene-options-come-from-capabilities", () => {
  adminLive();
  const usable = api.renderLiveSceneChoices(["alice"]);
  assert.deepEqual(Array.from(usable), ["research", "governance"]);
  const values = optionValues(document.getElementById("live-scene"));
  assert.deepEqual(values, ["", "research", "governance"], "默认项必须是按人物原路由");
});
check("person-without-governance-has-no-option", () => {
  adminLive();
  const usable = api.renderLiveSceneChoices(["omar"]);
  assert.deepEqual(Array.from(usable), ["media"], "无席位/任职的人不得出现治理选项");
  assert.ok(!optionValues(document.getElementById("live-scene")).includes("governance"));
});
check("mixed-selection-intersects", () => {
  adminLive();
  const usable = api.renderLiveSceneChoices(["alice", "omar"]);
  assert.deepEqual(Array.from(usable), [], "两人没有共同场景时只留默认项");
});
check("company-options-are-real-intersection", () => {
  adminLive();
  api.renderLiveSceneChoices(["alice", "dave"]);
  const select = document.getElementById("live-scene");
  select.value = "governance";
  const companies = api.renderLiveCompanyChoices(["alice", "dave"]);
  assert.deepEqual(Array.from(companies), ["cufe"], "公司必须取真实可治理公司的交集");
  assert.equal(document.getElementById("live-company").value, "cufe");
  assert.equal(document.getElementById("live-company-field").hidden, false);
});
check("company-field-hidden-without-governance", () => {
  adminLive();
  api.renderLiveSceneChoices(["alice"]);
  document.getElementById("live-scene").value = "";
  api.renderLiveCompanyChoices(["alice"]);
  assert.equal(document.getElementById("live-company-field").hidden, true);
  assert.equal(document.getElementById("live-scene-note").textContent, "");
});

// ---------------------------------------------------------------- 3 请求体
check("auto-route-body-keeps-old-shape", () => {
  adminLive();
  api.renderLiveSceneChoices(["alice"]);
  document.getElementById("live-scene").value = "";
  const body = api.liveStartBody(["alice"]);
  assert.deepEqual(Object.keys(body).sort(), ["expectedPosition", "expectedRevision", "persons"]);
  assert.equal(body.expectedRevision, "5");
  assert.equal(body.expectedPosition, "7");
});
check("governance-body-carries-scene-and-company", () => {
  adminLive();
  api.renderLiveSceneChoices(["alice"]);
  document.getElementById("live-scene").value = "governance";
  api.renderLiveCompanyChoices(["alice"]);
  const body = api.liveStartBody(["alice"]);
  assert.deepEqual(Object.keys(body).sort(),
    ["companyId", "expectedPosition", "expectedRevision", "persons", "scene"]);
  assert.equal(body.scene, "governance");
  assert.equal(body.companyId, "cufe");
  assert.deepEqual(Array.from(body.persons), ["alice"]);
});
check("governance-without-company-blocks-start", () => {
  adminLive();
  api.renderLiveSceneChoices(["alice"]);
  document.getElementById("live-scene").value = "governance";
  document.getElementById("live-company").replaceChildren();
  document.getElementById("live-company").value = "";
  assert.equal(api.liveStartBody(["alice"]), null, "没有公司时不能发出治理请求");
  assert.ok(document.getElementById("live-error").textContent.includes("治理公司"));
});

// ---------------------------------------------------------------- 4 真实提交路径
check("submit-handler-posts-allowlisted-body", () => {
  adminLive();
  fetchCalls.length = 0;
  selectPersons(["dave"]);
  api.renderLiveSceneChoices(["dave"]);
  document.getElementById("live-scene").value = "governance";
  api.renderLiveCompanyChoices(["dave"]);
  document.getElementById("live-company").value = "eng-a";
  fire(document.getElementById("live-start-form"), "submit");
  assert.equal(fetchCalls.length, 1, "提交处理器必须真的发请求");
  assert.equal(fetchCalls[0].url, "/api/admin/live/start");
  const body = JSON.parse(fetchCalls[0].options.body);
  assert.deepEqual(Object.keys(body).sort(),
    ["companyId", "expectedPosition", "expectedRevision", "persons", "scene"]);
  assert.equal(body.scene, "governance");
  assert.equal(body.companyId, "eng-a");
  assert.deepEqual(Array.from(body.persons), ["dave"]);
});
check("submit-handler-auto-route-omits-new-fields", () => {
  adminLive();
  fetchCalls.length = 0;
  selectPersons(["alice"]);
  api.renderLiveSceneChoices(["alice"]);
  document.getElementById("live-scene").value = "";
  fire(document.getElementById("live-start-form"), "submit");
  assert.equal(fetchCalls.length, 1);
  const body = JSON.parse(fetchCalls[0].options.body);
  assert.deepEqual(Object.keys(body).sort(), ["expectedPosition", "expectedRevision", "persons"]);
});
check("submit-without-person-does-not-call", () => {
  adminLive();
  fetchCalls.length = 0;
  selectPersons([]);
  fire(document.getElementById("live-start-form"), "submit");
  assert.equal(fetchCalls.length, 0);
  assert.ok(document.getElementById("live-error").textContent.includes("人物"));
});

// ---------------------------------------------------------------- 5 人物池（纯董事）
/** 当前勾选列表里的人物标识（按渲染顺序）。 */
function personValues() {
  const list = document.getElementById("live-persons");
  return list.children
    .filter(child => child.tag === "label" && child.children.length)
    .map(child => child.children[0].value);
}

check("governance-pool-has-pure-director", () => {
  adminLive();
  document.getElementById("live-scene").value = "governance";
  api.renderLivePersons();
  assert.deepEqual(Array.from(personValues()), ["alice", "dave", "erin"],
    "治理场景才出现纯董事");
});
check("auto-pool-excludes-pure-director", () => {
  adminLive();
  document.getElementById("live-scene").value = "";
  api.renderLivePersons();
  assert.deepEqual(Array.from(personValues()), ["alice", "dave", "omar"],
    "自动路由不得偷偷纳入纯董事");
  assert.equal(api.livePersonPool().length, 3);
});
check("omar-never-in-governance-pool", () => {
  adminLive();
  document.getElementById("live-scene").value = "governance";
  api.renderLivePersons();
  assert.ok(!Array.from(personValues()).includes("omar"), "无席无任职的人不在治理名单");
});
check("switch-back-drops-pure-director", () => {
  adminLive();
  document.getElementById("live-scene").value = "governance";
  selectPersons(["erin", "alice"]);
  api.renderLivePersons();
  assert.deepEqual(Array.from(personValues()), ["alice", "dave", "erin"]);
  // 切回自动路由：池里没有 erin，旧勾选必须立即清掉，请求体也不带场景字段。
  document.getElementById("live-scene").value = "";
  selectPersons(["erin", "alice"]);
  const kept = Array.from(api.renderLivePersons());
  assert.deepEqual(kept, ["alice"], "切回自动必须清掉不兼容选人");
  assert.ok(!Array.from(personValues()).includes("erin"));
  const body = api.liveStartBody(["alice"]);
  assert.deepEqual(Object.keys(body).sort(),
    ["expectedPosition", "expectedRevision", "persons"]);
});

// ---------------------------------------------------------------- 6 身份切换清空
check("identity-switch-clears-round-choice", () => {
  adminLive();
  api.renderLiveSceneChoices(["alice"]);
  document.getElementById("live-scene").value = "governance";
  api.renderLiveCompanyChoices(["alice"]);
  document.getElementById("live-company").value = "cufe";
  document.getElementById("live-scene-note").textContent = "本轮用“公司治理”配置";
  api.clearAdminDom();
  assert.equal(api.state.capabilities, null, "切换身份必须清空能力缓存");
  assert.deepEqual(optionValues(document.getElementById("live-scene")), [""]);
  assert.equal(document.getElementById("live-scene").value, "");
  assert.deepEqual(optionValues(document.getElementById("live-company")), []);
  assert.equal(document.getElementById("live-company-field").hidden, true);
  assert.equal(document.getElementById("live-scene-note").textContent, "");
  assert.equal(document.getElementById("live-round").textContent, "尚未开始本轮。");
});
check("round-summary-shows-scene-persons-company", () => {
  const summary = api.liveRoundSummary({ roundScene: "governance", roundCompany: "cufe",
    roundPersons: ["alice", "dave"] });
  assert.ok(summary.includes("公司治理"), summary);
  assert.ok(summary.includes("cufe"), summary);
  assert.ok(summary.includes("alice") && summary.includes("dave"), summary);
  assert.equal(api.liveRoundSummary({ roundPersons: [], roundScene: null }), "尚未开始本轮。");
  assert.ok(api.liveRoundSummary({ roundPersons: ["alice"], roundScene: null })
    .includes("按人物原路由"));
});
check("no-secret-text-in-live-dom", () => {
  const text = [document.getElementById("live-scene-note").textContent,
    document.getElementById("live-round").textContent,
    document.getElementById("live-error").textContent].join("|");
  assert.ok(!/sk-|Bearer|api[_-]?key/i.test(text), text);
});

const failed = cases.filter(([status]) => status === "FAIL");
for (const [status, name] of cases) console.log(status + " " + name);
console.log("GOVERNANCE_LIVE_SCENE_CONTRACT=" + (failed.length ? "FAIL" : "PASS")
  + " passed=" + (cases.length - failed.length) + "/" + cases.length);
if (failed.length) process.exit(1);
