// 独立 UI 探针：加载真实 app.js，用真实历史页函数与真实监听器驱动；只断言可观察结果。
// 用法：node history-ui-async-probe.cjs <webDir>
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const webDir = process.argv[2] || path.join(__dirname, "..", "..", "main", "resources", "web");
const source = fs.readFileSync(path.join(webDir, "app.js"), "utf8");

let allCreated = [];
function makeElement(tag) {
  const node = {
    tag, attrs: {}, children: [], _listeners: {}, _text: "", hidden: false, value: "",
    checked: false, disabled: false, type: "text", name: "", id: "", rows: [],
    dataset: {}, classList: { add() { }, remove() { }, toggle() { } }, _detached: false,
    _childBySelector: new Map(), open: false, href: "", download: "",
    get textContent() { return this._text; },
    set textContent(v) { this._text = v === null || v === undefined ? "" : String(v); },
    setAttribute(k, v) { this.attrs[k] = String(v); },
    getAttribute(k) { return this.attrs[k] === undefined ? null : this.attrs[k]; },
    removeAttribute(k) { delete this.attrs[k]; },
    appendChild(child) { child._detached = false; this.children.push(child); return child; },
    append(...items) { for (const item of items) { item._detached = false; this.children.push(item); } },
    replaceChildren(...items) { for (const child of this.children) markDetached(child); this.children = items; for (const item of items) item._detached = false; },
    addEventListener(type, handler) { (this._listeners[type] = this._listeners[type] || []).push(handler); },
    querySelector(selector) {
      if (!this._childBySelector.has(selector)) {
        const child = makeElement("queried:" + selector);
        this._childBySelector.set(selector, child);
        this.children.push(child);
      }
      return this._childBySelector.get(selector);
    },
    querySelectorAll(selector) {
      if (String(selector) === "details[open]") return [];
      return [];
    },
    click() { this.clicked = (this.clicked || 0) + 1; },
    focus() { this.focused = true; },
    close(reason) { this.open = false; }
  };
  allCreated.push(node);
  return node;
}
function markDetached(node) { node._detached = true; for (const c of node.children) markDetached(c); }

const byId = new Map();
const document = {
  body: makeElement("body"),
  getElementById(id) {
    if (!byId.has(id)) { const node = makeElement("byId:" + id); node.id = id; byId.set(id, node); }
    return byId.get(id);
  },
  createElement(tag) { return makeElement(tag); },
  createElementNS(ns, tag) { return makeElement(tag); },
  createTextNode(text) { const node = makeElement("#text"); node.textContent = text; return node; },
  querySelector() { return null; },
  querySelectorAll(selector) {
    const raw = String(selector);
    if (raw === "[data-panel]" || raw === "[data-page]") return [];
    if (raw.includes('name="live-person"')) {
      return allCreated.filter(n => n.tag === "input" && n.name === "live-person" && n.checked && !n._detached);
    }
    return [];
  },
  addEventListener() { }
};

const fetchLog = [];
let responder = null;
function respond(status, payload, text) {
  return Promise.resolve({ status, json: async () => payload, text: async () => text });
}
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
function fakeFetch(url, options) {
  const method = (options && options.method) || "GET";
  fetchLog.push({ url, method });
  return responder(url, method);
}
function flush(times = 14) {
  let chain = Promise.resolve();
  for (let i = 0; i < times; i++) chain = chain.then(() => new Promise(r => setImmediate(r)));
  return chain;
}

const timers = [];
const context = vm.createContext({
  document, console, BigInt, Number, String, Boolean, Object, Array, Map, Set, JSON,
  Math, Date, Promise, Error, RegExp,
  setTimeout(fn) { timers.push(fn); return timers.length; },
  clearTimeout() { }, setInterval() { return 0; }, clearInterval() { },
  fetch: fakeFetch, URL, Blob,
  nextGeneration: () => 0
});
vm.runInContext(source, context);
const groupsSource = fs.readFileSync(path.join(webDir, "groups.js"), "utf8");
vm.runInContext(groupsSource, context);
vm.runInContext("globalThis.__api = { state, selectPage, showMode, clearHistoryDom,"
  + " loadHistoryIndex, openHistoryPosition, downloadHistoryCheckpoint,"
  + " verifyHistoryCheckpointText, historyState };", context);
const api = context.__api;

function resetState() {
  api.state.mode = "admin";
  api.state.page = "history";
  api.state.generation += 1;
  api.state.pageVersion += 1;
  api.state.history = null;
  api.clearHistoryDom();
  for (const id of ["history-after", "history-limit", "history-position-input"]) {
    document.getElementById(id).value = "";
  }
  document.getElementById("history-checkpoint-text").value = "";
  allCreated = allCreated.filter(n => !n.tag.includes("queried"));
}
function viewPayload(position, cut) {
  return {
    runId: "web-groups", position: String(position), cutPosition: String(cut),
    timeMillis: String(1000 + position), lastPhase: "OBSERVATION", historical: position < cut,
    overview: { total: 1, rows: [{ runId: "web-groups", position: String(position),
      cutPosition: String(cut), timeMillis: String(1000 + position), lastPhase: "OBSERVATION",
      accounts: "1", totalCash: "1000.00", pending: "0", delivered: "0", usedTaskIds: "0",
      modules: "securities,groups" }] },
    accounts: { total: 1, rows: [{ accountId: "A" + position, ownerId: "alice",
      cash: (1000 + position) + ".00", source: "open" }] },
    securities: { total: 0, rows: [] },
    pending: { total: 0, rows: [] },
    roles: { total: 0, rows: [] },
    information: { total: 0, rows: [] },
    engineering: { total: 0, rows: [] },
    groups: { total: 0, rows: [] },
    unsupported: [], notes: ["只读位置 " + position]
  };
}
function checkpointText(position) {
  return JSON.stringify({ checkpointVersion: "world-checkpoint-v1", runId: "web-groups",
    position: position, stateJson: "{}" });
}

const cases = [];
async function check(name, run) {
  try { await run(); cases.push(["PASS", name]); }
  catch (failure) { cases.push(["FAIL", name + " :: " + failure.message]); }
}

async function main() {
  responder = () => respond(401, { message: "no session" });
  await flush(6);

  await check("fast-position-switch-old-response-dropped", async () => {
    resetState();
    const two = deferred(), five = deferred();
    responder = (url) => url.includes("/view?position=2") ? two.promise
      : url.includes("/view?position=5") ? five.promise : respond(200, {});
    const older = api.openHistoryPosition(2);
    await flush(3);
    const newer = api.openHistoryPosition(5);
    await flush(3);
    five.resolve({ status: 200, json: async () => viewPayload(5, 7) });
    await newer;
    two.resolve({ status: 200, json: async () => viewPayload(2, 7) });
    await older;
    await flush(2);
    assert.equal(document.getElementById("history-position").textContent, "5",
      "旧位置回包不得覆盖后选位置");
    assert.equal(api.historyState().selected, 5);
  });

  await check("leave-page-late-view-not-rendered", async () => {
    resetState();
    const held = deferred();
    responder = (url) => url.includes("/view?position=4") ? held.promise : respond(200, {});
    const pending = api.openHistoryPosition(4);
    await flush(3);
    api.selectPage("overview", false);
    held.resolve({ status: 200, json: async () => viewPayload(4, 7) });
    await pending;
    await flush(2);
    assert.equal(document.getElementById("history-position").textContent, "—",
      "离开页面后迟到视图不得回填");
    assert.equal(api.historyState().selected, null, "离开页面必须清空所选位置");
  });

  await check("identity-switch-late-view-not-rendered", async () => {
    resetState();
    const held = deferred();
    responder = (url) => url.includes("/view?position=6") ? held.promise : respond(200, {});
    const pending = api.openHistoryPosition(6);
    await flush(3);
    api.showMode("login");
    held.resolve({ status: 200, json: async () => viewPayload(6, 7) });
    await pending;
    await flush(2);
    assert.notEqual(document.getElementById("history-position").textContent, "6",
      "切换身份后迟到视图不得回填");
    assert.equal(api.historyState().selected, null);
  });

  await check("export-must-match-displayed-frame", async () => {
    resetState();
    const shown = deferred();
    responder = (url) => url.includes("/view?position=5") ? shown.promise : respond(200, {});
    const open = api.openHistoryPosition(5);
    await flush(3);
    shown.resolve({ status: 200, json: async () => viewPayload(5, 7) });
    await open;
    await flush(2);
    assert.equal(document.getElementById("history-position").textContent, "5");
    document.getElementById("history-position-input").value = "6";
    const requests = [];
    responder = (url) => {
      if (url.includes("/history/checkpoint")) {
        requests.push(url);
        const pos = Number(url.split("position=")[1]);
        return respond(200, null, checkpointText(pos));
      }
      return respond(200, {});
    };
    await api.downloadHistoryCheckpoint();
    await flush(2);
    assert.equal(requests.length, 1);
    assert.ok(requests[0].endsWith("position=5"),
      "导出必须是当前显示的确认帧 5，而不是输入框新值：" + requests[0]);
  });

  await check("export-requires-displayed-frame", async () => {
    resetState();
    document.getElementById("history-position-input").value = "5";
    const requests = [];
    responder = (url) => { requests.push(url); return respond(200, {}); };
    await api.downloadHistoryCheckpoint();
    assert.equal(requests.length, 0, "未显示历史帧时不得直接请求导出");
    assert.ok(document.getElementById("history-checkpoint-status").textContent.includes("先回看"));
  });

  await check("late-export-after-leave-not-applied", async () => {
    resetState();
    responder = () => respond(200, viewPayload(5, 7));
    await api.openHistoryPosition(5);
    assert.equal(api.historyState().selected, 5);
    const held = deferred();
    document.getElementById("history-position-input").value = "5";
    const requests = [];
    responder = (url) => {
      if (url.includes("/history/checkpoint")) { requests.push(url); return held.promise; }
      return respond(200, {});
    };
    document.getElementById("history-checkpoint-text").value = "";
    const pending = api.downloadHistoryCheckpoint();
    await flush(3);
    assert.equal(requests.length, 1);
    api.selectPage("overview", false);
    held.resolve({ status: 200, html: null, text: async () => checkpointText(5) });
    await pending;
    await flush(2);
    assert.equal(document.getElementById("history-checkpoint-text").value, "",
      "离开页面后迟到导出不得写入导出上下文");
  });

  await check("export-context-cleared-on-leave", async () => {
    resetState();
    document.getElementById("history-checkpoint-text").value = "OLD-CHECKPOINT";
    document.getElementById("history-checkpoint-status").textContent = "已导出位置 3";
    api.selectPage("overview", false);
    assert.equal(document.getElementById("history-checkpoint-text").value, "",
      "离开历史页必须清空已导出的检查点文本");
  });

  await check("history-page-no-business-writes", async () => {
    resetState();
    const calls = [];
    responder = (url, method) => {
      calls.push([method, url]);
      if (url.startsWith("/api/admin/history?")) {
        return respond(200, { runId: "web-groups", cutPosition: "7", entries: [], nextAfter: "-1" });
      }
      if (url.includes("/history/view")) return respond(200, viewPayload(3, 7));
      if (url.includes("/history/checkpoint")) return respond(200, null, checkpointText(3));
      if (url.includes("/history/verify")) return respond(200, { ok: true, reason: "OK", runId: "web-groups", position: 3 });
      return respond(200, {});
    };
    document.getElementById("history-after").value = "0";
    document.getElementById("history-limit").value = "50";
    document.getElementById("history-position-input").value = "3";
    await api.loadHistoryIndex();
    await api.openHistoryPosition(3);
    await api.downloadHistoryCheckpoint();
    await api.verifyHistoryCheckpointText(checkpointText(3));
    const bad = calls.filter(([method, url]) => url.includes("/api/admin/advance")
      || url.includes("/api/session") || url.startsWith("/api/me/")
      || (method === "POST" && !url.includes("/history/verify")));
    assert.deepEqual(bad, [], "历史页不得产生业务写请求：" + JSON.stringify(bad));
  });

  const failed = cases.filter(([s]) => s === "FAIL");
  for (const [status, name] of cases) console.log(status + " " + name);
  console.log("HISTORY_UI_PROBE=" + (failed.length ? "FAIL" : "PASS")
    + " passed=" + (cases.length - failed.length) + "/" + cases.length);
  if (failed.length) process.exit(1);
}

main().catch(f => { console.error(f); process.exit(1); });
