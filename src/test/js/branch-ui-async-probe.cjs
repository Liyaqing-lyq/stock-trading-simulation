// 独立 B2 分支页异步探针：加载真实 app.js，用真实 branch 函数与 fetch 路径驱动。
// 只断言可观察 DOM/导航结果；用于检查过期响应是否按 generation/pageVersion 丢弃。
// 用法：node src/test/js/branch-ui-async-probe.cjs [webDir]
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const webDir = process.argv[2] || path.resolve(__dirname, "../../main/resources/web");
const source = fs.readFileSync(path.join(webDir, "app.js"), "utf8");

let created = [];
function el(tag) {
  const node = {
    tag, children: [], _listeners: {}, _text: "", hidden: false, value: "", disabled: false,
    dataset: {}, _childBySelector: new Map(), href: "", download: "", clicked: 0,
    get textContent() { return this._text; },
    set textContent(v) { this._text = v == null ? "" : String(v); },
    setAttribute() { }, getAttribute() { return null; }, removeAttribute() { },
    appendChild(c) { this.children.push(c); return c; },
    append(...cs) { this.children.push(...cs); },
    replaceChildren(...cs) { this.children = cs; },
    addEventListener(t, h) { (this._listeners[t] = this._listeners[t] || []).push(h); },
    querySelector(s) {
      if (!this._childBySelector.has(s)) {
        const c = el("q:" + s); this._childBySelector.set(s, c); this.children.push(c);
      }
      return this._childBySelector.get(s);
    },
    querySelectorAll() { return []; },
    click() { this.clicked += 1; }, focus() { }, close() { },
    classList: { add() { }, remove() { }, toggle() { } }, open: false
  };
  created.push(node);
  return node;
}
const byId = new Map();
const document = {
  body: el("body"),
  getElementById(id) {
    if (!byId.has(id)) { const n = el("id:" + id); n.id = id; byId.set(id, n); }
    return byId.get(id);
  },
  createElement(t) { return el(t); },
  createElementNS(ns, t) { return el(t); },
  createTextNode(t) { const n = el("#text"); n.textContent = t; return n; },
  querySelector() { return null; },
  querySelectorAll() { return []; },
  addEventListener() { }
};

const fetchLog = [];
let responder = null;
const navigations = [];
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
function respond(status, payload) {
  return Promise.resolve({ status, json: async () => payload, text: async () => JSON.stringify(payload) });
}
function flush(times = 16) {
  let c = Promise.resolve();
  for (let i = 0; i < times; i++) c = c.then(() => new Promise(r => setImmediate(r)));
  return c;
}
function fakeFetch(url, options) {
  const method = (options && options.method) || "GET";
  fetchLog.push({ url, method });
  return responder(url, method);
}

const context = vm.createContext({
  document, console, BigInt, Number, String, Boolean, Object, Array, Map, Set, JSON,
  Math, Date, Promise, Error, RegExp,
  setTimeout() { return 1; }, clearTimeout() { }, setInterval() { return 0; },
  clearInterval() { }, fetch: fakeFetch, URL,
  Blob: function Blob(parts) { this.parts = parts; },
  window: { location: { assign(url) { navigations.push(url); } } }
});
vm.runInContext(source, context);
vm.runInContext("globalThis.__api = { state, branchState, clearBranchDom, loadBranchRuns,"
  + " renderBranchRuns, checkBranchSupport, createBranchRun, runBranchAction };", context);
const api = context.__api;

function reset() {
  api.state.mode = "admin";
  api.state.page = "history";
  api.state.generation += 1;
  api.state.pageVersion += 1;
  api.state.branch = null;
  api.clearBranchDom();
  document.getElementById("branch-cut").value = "";
  document.getElementById("branch-child-id").value = "";
}
function runView(status, managed = true, port = 18100) {
  return { current: { runId: "web-groups", parentRunId: "", parentPosition: 0, position: 5,
      firstPosition: 1 }, launcher: true, maxRuns: 2, active: status === "RUNNING" ? 1 : 0,
    runs: [{ runId: "child-alpha", parentRunId: "web-groups", parentPosition: 1,
      firstPosition: 2, port, status, reason: "", managed }] };
}
function branchButtons() {
  const body = document.getElementById("branch-runs").querySelector("tbody");
  const rows = body.children || [];
  if (!rows.length) return [];
  const row = rows[rows.length - 1];
  const actionCell = row.children[row.children.length - 1];
  return actionCell.children || [];
}

const cases = [];
async function check(name, run) {
  try { await run(); cases.push(["PASS", name]); }
  catch (f) { cases.push(["FAIL", name + " :: " + f.message]); }
}

async function main() {
  responder = () => respond(401, {});
  await flush(8);

  await check("stale-start-response-dropped-after-identity-change", async () => {
    reset();
    const held = deferred();
    const generation = api.state.generation;
    responder = (url, method) => {
      if (url.includes("/api/admin/runs/child-alpha/start")) return held.promise;
      if (url === "/api/admin/runs") return respond(200, runView("RUNNING"));
      return respond(200, {});
    };
    const pending = api.runBranchAction("child-alpha", "start");
    await flush(4);
    assert.ok(api.state.generation === generation);
    // 模拟退出后以新管理员/新代次重新进入同一历史页。
    api.state.generation += 1;
    api.state.pageVersion += 1;
    api.state.mode = "admin";
    api.state.page = "history";
    held.resolve(respond(200, { runId: "child-alpha", status: "RUNNING" }));
    await pending;
    await flush(4);
    assert.equal(document.getElementById("branch-status").textContent, "",
      "旧代次的 start 回包不得写新身份的 branch-status："
      + document.getElementById("branch-status").textContent);
  });

  await check("stale-support-response-dropped-after-new-position", async () => {
    reset();
    const one = deferred(), two = deferred();
    responder = (url) => {
      if (url.includes("branches?position=1")) return one.promise;
      if (url.includes("branches?position=2")) return two.promise;
      return respond(200, {});
    };
    document.getElementById("branch-cut").value = "1";
    const first = api.checkBranchSupport();
    await flush(3);
    document.getElementById("branch-cut").value = "2";
    const second = api.checkBranchSupport();
    await flush(3);
    two.resolve(respond(200, { position: "2", cutPosition: "5", supported: true, reasons: [] }));
    await second;
    one.resolve(respond(200, { position: "1", cutPosition: "5", supported: false,
      reasons: ["BRANCH_BARRIER_PENDING"] }));
    await first;
    await flush(2);
    assert.ok(document.getElementById("branch-support").textContent.includes("位置 2"),
      "旧位置的 support 回包不得覆盖新位置："
      + document.getElementById("branch-support").textContent);
    assert.equal(document.getElementById("branch-create").disabled, false,
      "新位置可用时创建按钮不应被旧响应关掉");
  });

  await check("branch-buttons-follow-server-status-and-enter-navigates", async () => {
    reset();
    api.renderBranchRuns(runView("RUNNING"));
    let buttons = branchButtons();
    assert.equal(buttons[0].disabled, true, "RUNNING 时启动必须禁用");
    assert.equal(buttons[1].disabled, false, "RUNNING 时进入必须可用");
    assert.equal(buttons[2].disabled, false, "自有 RUNNING 时停止必须可用");
    api.renderBranchRuns(runView("CREATED"));
    buttons = branchButtons();
    assert.equal(buttons[1].disabled, true, "CREATED 时进入必须禁用");
    navigations.length = 0;
    responder = () => respond(200, { location: "http://127.0.0.1:18100/" });
    await api.runBranchAction("child-alpha", "enter");
    assert.deepEqual(navigations, ["http://127.0.0.1:18100/"], "进入必须按服务端 location 导航");
  });

  await check("clear-branch-dom-resets-state", async () => {
    reset();
    api.renderBranchRuns(runView("RUNNING"));
    api.clearBranchDom();
    assert.equal(api.state.branch, null);
    assert.equal(document.getElementById("branch-run").textContent, "—");
    assert.equal(branchButtons().length, 0);
  });

  // ===== 作者补充：原探针只覆盖 stale-start，这里补真实 stale-enter 与 stale-stop =====
  await check("stale-enter-response-never-navigates", async () => {
    reset();
    api.renderBranchRuns(runView("RUNNING"));
    const held = deferred();
    const generation = api.state.generation;
    responder = (url, method) => {
      if (url.includes("/child-alpha/enter")) return held.promise;
      if (url === "/api/admin/runs") return respond(200, runView("RUNNING"));
      return respond(200, {});
    };
    navigations.length = 0;
    const pending = api.runBranchAction("child-alpha", "enter");
    await flush(4);
    assert.ok(api.state.generation === generation);
    // 退出后以新代次重新进入同一页：旧 enter 回包不得把浏览器跳去旧子进程。
    api.state.generation += 1;
    api.state.pageVersion += 1;
    api.state.mode = "admin";
    api.state.page = "history";
    held.resolve(respond(200, { location: "http://127.0.0.1:18100/" }));
    await pending;
    await flush(4);
    assert.deepEqual(navigations, [], "旧代次的 enter 回包不得触发导航");
    assert.equal(document.getElementById("branch-status").textContent, "",
      "旧代次的 enter 回包不得留下任何状态：" + document.getElementById("branch-status").textContent);
  });

  await check("stale-stop-response-dropped-after-new-run-selection", async () => {
    reset();
    api.renderBranchRuns(runView("RUNNING"));
    const held = deferred();
    const branch = api.state.branch;
    responder = (url, method) => {
      if (url.includes("/child-alpha/stop")) return held.promise;
      if (url === "/api/admin/runs") return respond(200, runView("STOPPED"));
      return respond(200, {});
    };
    const pending = api.runBranchAction("child-alpha", "stop");
    await flush(4);
    // 同页发起第二个动作（新动作序号）：旧的 stop 回包不得再写状态。
    api.state.branch = branch;
    branch.actionRequest += 1;
    document.getElementById("branch-status").textContent = "新的动作进行中…";
    held.resolve(respond(200, { runId: "child-alpha", status: "STOPPED" }));
    await pending;
    await flush(4);
    assert.equal(document.getElementById("branch-status").textContent, "新的动作进行中…",
      "旧动作回包不得覆盖更新的动作状态");
  });

  await check("create-requires-checked-support-of-same-position", async () => {
    reset();
    responder = () => respond(200, {});
    document.getElementById("branch-cut").value = "2";
    document.getElementById("branch-child-id").value = "child-alpha";
    const before = fetchLog.length;
    await api.createBranchRun();
    assert.equal(fetchLog.length, before, "未核过的位置不得直接发起创建请求");
    assert.ok(document.getElementById("branch-error").textContent.includes("检查"),
      "必须提示先检查该位置的可行性：" + document.getElementById("branch-error").textContent);
  });

  await check("stale-response-does-not-clear-new-same-text-action", async () => {
    reset();
    const first = deferred(), second = deferred();
    let calls = 0;
    responder = (url) => {
      if (url.includes("/api/admin/runs/child-alpha/start")) {
        calls += 1;
        return calls === 1 ? first.promise : second.promise;
      }
      if (url === "/api/admin/runs") return respond(200, runView("RUNNING"));
      return respond(200, {});
    };
    const oldAction = api.runBranchAction("child-alpha", "start");
    await flush(3);
    const newAction = api.runBranchAction("child-alpha", "start");
    await flush(3);
    assert.equal(document.getElementById("branch-status").textContent,
      "正在启动 child-alpha…", "新动作应保留自己的 busy 文案");
    first.resolve(respond(200, { runId: "child-alpha", status: "RUNNING" }));
    await oldAction;
    await flush(2);
    assert.equal(document.getElementById("branch-status").textContent,
      "正在启动 child-alpha…",
      "旧动作的迟到回包不得清掉新动作同文案的 busy 提示");
    second.resolve(respond(200, { runId: "child-alpha", status: "RUNNING" }));
    await newAction;
    await flush(2);
  });

  await check("new-branch-object-reset-actionrequest-collision-does-not-clear", async () => {
    reset();
    const first = deferred(), second = deferred();
    let calls = 0;
    responder = (url) => {
      if (url.includes("/api/admin/runs/child-alpha/start")) {
        calls += 1;
        return calls === 1 ? first.promise : second.promise;
      }
      if (url === "/api/admin/runs") return respond(200, runView("RUNNING"));
      return respond(200, {});
    };
    const oldAction = api.runBranchAction("child-alpha", "start");
    await flush(3);
    // 离页/清空后新 branch 对象的 actionRequest 从 1 重新开始，和旧请求撞号。
    api.clearBranchDom();
    const newAction = api.runBranchAction("child-alpha", "start");
    await flush(3);
    assert.equal(document.getElementById("branch-status").textContent,
      "正在启动 child-alpha…", "新分支对象的新动作应保留 busy 文案");
    first.resolve(respond(200, { runId: "child-alpha", status: "RUNNING" }));
    await oldAction;
    await flush(2);
    assert.equal(document.getElementById("branch-status").textContent,
      "正在启动 child-alpha…",
      "旧 branch 对象的迟到回包（即使 requestId 撞号）不得清新对象 busy");
    second.resolve(respond(200, { runId: "child-alpha", status: "RUNNING" }));
    await newAction;
    await flush(2);
  });

  const failed = cases.filter(([s]) => s === "FAIL");
  for (const [status, name] of cases) console.log(status + " " + name);
  console.log("BRANCH_UI_PROBE=" + (failed.length ? "FAIL" : "PASS")
    + " passed=" + (cases.length - failed.length) + "/" + cases.length);
  if (failed.length) process.exit(1);
}

main().catch(f => { console.error(f); process.exit(1); });
