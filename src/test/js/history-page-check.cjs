"use strict";

// 真实执行 app.js 里的历史回看函数（不联网）：用最小 DOM 桩验证
// 1) 索引只读请求与固定位置视图渲染；
// 2) 离开页面/切换位置/切换身份后，迟到的历史回包不得回填；
// 3) 历史路径不产生任何业务写请求（无 advance、无 POST）。
const fs = require("fs");
const path = require("path");
const vm = require("vm");
const assert = require("assert");

const root = process.argv[2] || path.join(__dirname, "..", "..", "main", "resources", "web");
const app = fs.readFileSync(path.join(root, "app.js"), "utf8");

// 整段取「历史回看」区块（从区块注释到第一个监听器注册之前），一次求值：
// 区块内只有函数与一个常量声明，没有顶层副作用。
const blockStart = app.indexOf("// ---------------------------------------------------------------- 历史回看");
const blockEnd = app.indexOf('$("login-form").addEventListener');
assert(blockStart >= 0 && blockEnd > blockStart, "找不到历史回看区块");
const historyBlock = app.slice(blockStart, blockEnd);
assert(historyBlock.includes("function renderHistoryView"), "区块缺少历史视图函数");

const nodes = new Map();
function node(id) {
  if (!nodes.has(id)) {
    const body = {
      children: [],
      replaceChildren() { this.children = []; },
      appendChild(child) { this.children.push(child); return child; }
    };
    const element = {
      id, hidden: false, textContent: "", value: "", rows: [], body,
      children: [],
      appendChild(child) { this.children.push(child); return child; },
      addEventListener() {},
      querySelector(selector) { return selector === "tbody" ? body : null; }
    };
    nodes.set(id, element);
  }
  return nodes.get(id);
}

const state = { mode: "admin", generation: 3, page: "history", pageVersion: 11, history: null };
const calls = [];
const pending = new Map();
const downloads = [];
let checkpointResponder = null;

function checkpointResponse(position) {
  return {
    status: 200,
    text: async () => JSON.stringify({ checkpointVersion: "world-checkpoint-v1",
      runId: "web-groups", position: position, stateJson: "{}" })
  };
}

function response(payload) {
  return { status: 200, payload };
}

const context = {
  state,
  console,
  Number,
  String,
  JSON,
  Date,
  $: node,
  isCurrent: generation => generation === state.generation,
  nextGeneration: () => ++state.generation,
  sessionLost: () => { calls.push(["sessionLost"]); },
  show: (id, visible) => { node(id).hidden = !visible; },
  setMessage: (id, text) => { node(id).textContent = text; node(id).hidden = !text; },
  cell: (tr, value) => { tr.children.push({ textContent: value === undefined ? "" : String(value) }); },
  fillTable: (id, rows, build) => {
    const table = node(id);
    table.rows = rows || [];
    table.children = [];
    for (const row of table.rows) {
      const tr = { children: [], appendChild(child) { this.children.push(child); return child; } };
      build(tr, row);
      table.children.push(tr);
    }
  },
  guard: fn => fn(),
  document: {
    createElement: tag => ({ tag, textContent: "", href: "", download: "", children: [],
      appendChild(child) { this.children.push(child); return child; },
      addEventListener() {},
      click() { if (tag === "a") downloads.push(this.download); } }),
    querySelector: () => null
  },
  downloadHistoryCheckpointProbe: null,
  Blob: function Blob(parts) { this.parts = parts; },
  URL: { createObjectURL: () => "blob:probe", revokeObjectURL: () => undefined },
  fetch: async url => {
    calls.push(["FETCH", url]);
    if (checkpointResponder) {
      return checkpointResponder(url);
    }
    throw new Error("未预期的 fetch：" + url);
  },
  request: async (method, url) => {
    calls.push([method, url]);
    const key = url.replace(/^https?:/, "");
    if (pending.has(key)) {
      const entry = pending.get(key);
      pending.delete(key);
      return entry;
    }
    if (key.startsWith("/api/admin/history?")) {
      return deferred(key);
    }
    if (key.startsWith("/api/admin/history/view?")) {
      return deferred(key);
    }
    if (key === "/api/admin/history/verify") {
      return response({ ok: false, reason: "CHECKPOINT_STATE_MISMATCH", runId: "web-groups", position: 3 });
    }
    throw new Error("未预期的请求：" + method + " " + url);
  }
};

function deferred(key) {
  let release;
  const promise = new Promise(resolve => { release = resolve; });
  pending.set(key, promise);
  // 释放后立刻摘除：同一 URL 再次请求必须拿到新的 deferred，不能复用已完成的旧回包。
  promise.release = value => { pending.delete(key); release(value); };
  return promise;
}

vm.createContext(context);
vm.runInContext(historyBlock, context);

const indexPayload = {
  runId: "web-groups",
  cutPosition: "7",
  entries: [
    { position: "1", commandId: "__open__", result: "OPENED", eventCount: 4 },
    { position: "2", commandId: "doc-1", result: "PUBLISHED", eventCount: 3 }
  ],
  nextAfter: "2"
};

function viewPayload(position) {
  return {
    runId: "web-groups",
    position: String(position),
    cutPosition: "7",
    timeMillis: "0",
    lastPhase: "OBSERVATION",
    historical: position < 7,
    overview: { total: 1, rows: [{ runId: "web-groups", position: String(position), cutPosition: "7",
      timeMillis: "0", lastPhase: "OBSERVATION", accounts: "3", totalCash: "3000.00",
      pending: "2", delivered: "0", usedTaskIds: "5", modules: "securities,groups" }] },
    accounts: { total: 1, rows: [{ accountId: "A1", ownerId: "alice", cash: "1000.00", source: "open" }] },
    securities: { total: 0, rows: [] },
    pending: { total: 1, rows: [{ taskId: "t1", dueTimeMillis: "1000", phase: "COMPLETION", kind: "TRANSFER" }] },
    roles: { total: 1, rows: [{ appointmentId: "ap1", personPartyId: "alice",
      organizationPartyId: "org", role: "CHAIR", startsAt: "0", endsAt: "100", revokedAt: "" }] },
    information: { total: 0, rows: [] },
    engineering: { total: 0, rows: [] },
    groups: { total: 1, rows: [{ groupId: "g-open", ownerPartyId: "alice", joinPolicy: "OPEN",
      historyPolicy: "JOIN_TIME", createdAt: "0", openMembers: "2", posts: "0" }] },
    unsupported: ["本运行未启用按公司工程经营，工程分区不显示。"],
    notes: ["本页只读取位置 " + position + " 的已确认快照。"]
  };
}

(async () => {
  // 1) 索引：真实读取并按位置渲染
  node("history-after").value = "0";
  node("history-limit").value = "50";
  const indexCall = context.loadHistoryIndex();
  const indexKey = "/api/admin/history?after=0&limit=50";
  await Promise.resolve();
  pending.get(indexKey).release(response(indexPayload));
  await indexCall;
  assert.deepStrictEqual(calls[0], ["GET", indexKey], "索引必须是只读 GET");
  assert.strictEqual(node("history-cut").textContent, "7");
  assert.strictEqual(node("history-index").rows.length, 2);
  assert.strictEqual(node("history-index").children.length, 2, "索引两行都要渲染");

  // 2) 固定位置视图：多面板来自同一份返回
  const viewCall = context.openHistoryPosition(3);
  await Promise.resolve();
  pending.get("/api/admin/history/view?position=3").release(response(viewPayload(3)));
  await viewCall;
  assert.strictEqual(node("history-position").textContent, "3");
  assert.strictEqual(node("history-position-cut").textContent, "7");
  assert.strictEqual(node("history-accounts").rows.length, 1);
  assert.strictEqual(node("history-groups").rows[0].groupId, "g-open");
  assert.strictEqual(node("history-roles").rows[0].appointmentId, "ap1");
  assert.ok(node("history-mode").textContent.includes("早于当前确认截止"),
    "历史位置必须明确标成回看");
  assert.ok(node("history-unsupported").textContent.includes("工程"),
    "缺模块必须明确说明，不能显示当前值");

  // 3) 离开页面：迟到的视图回包不得回填（与真实 selectPage 一样先清空再换页）
  const lateLeave = context.openHistoryPosition(4);
  await Promise.resolve();
  context.clearHistoryDom();
  state.page = "overview";           // 模拟 selectPage 离开
  state.pageVersion += 1;
  pending.get("/api/admin/history/view?position=4").release(response(viewPayload(4)));
  await lateLeave;
  assert.strictEqual(node("history-position").textContent, "—",
    "离开页面后迟到的历史回包不得回填");

  // 4) 切换位置：先发后到的旧位置不得覆盖新位置
  state.page = "history";
  state.pageVersion += 1;
  const older = context.openHistoryPosition(2);
  await Promise.resolve();
  const newer = context.openHistoryPosition(5);
  await Promise.resolve();
  pending.get("/api/admin/history/view?position=5").release(response(viewPayload(5)));
  await newer;
  pending.get("/api/admin/history/view?position=2").release(response(viewPayload(2)));
  await older;
  assert.strictEqual(node("history-position").textContent, "5",
    "旧位置的回包不得覆盖后选的位置");

  // 5) 切换身份：代次变化后迟到回包不得回填
  const lateIdentity = context.openHistoryPosition(6);
  await Promise.resolve();
  context.nextGeneration();
  pending.get("/api/admin/history/view?position=6").release(response(viewPayload(6)));
  await lateIdentity;
  assert.strictEqual(node("history-position").textContent, "5",
    "切换身份后迟到的历史回包不得回填");

  // 6) 核验：服务端拒绝时如实显示原因
  await context.verifyHistoryCheckpointText("{\"checkpointVersion\":\"world-checkpoint-v1\"}");
  assert.ok(node("history-verify-result").textContent.includes("CHECKPOINT_STATE_MISMATCH"),
    "核验拒绝原因必须可见");

  // 7) F2：导出位置以已显示的确认帧为准，输入框新值不得改变导出对象
  node("history-checkpoint-text").value = "";
  const shownView = context.openHistoryPosition(3);
  await Promise.resolve();
  pending.get("/api/admin/history/view?position=3").release(response(viewPayload(3)));
  await shownView;
  assert.strictEqual(state.history.selected, 3, "显示成功后必须记住所选位置");
  node("history-position-input").value = "9";
  const exportUrls = [];
  checkpointResponder = url => { exportUrls.push(url); return checkpointResponse(3); };
  await context.downloadHistoryCheckpoint();
  assert.strictEqual(exportUrls.length, 1, "应只发一次导出请求");
  assert.ok(exportUrls[0].endsWith("position=3"),
    "导出必须用显示中的位置 3，而不是输入框的 9：" + exportUrls[0]);
  assert.ok(node("history-checkpoint-text").value.includes("\"position\":3"));
  assert.deepStrictEqual(downloads, ["world-checkpoint-3.json"], "落盘文件必须对应显示位置");

  // 8) F3：导出在途时离页，迟到回包不得写文本、不得触发下载
  node("history-checkpoint-text").value = "";
  downloads.length = 0;
  let releaseExport;
  checkpointResponder = () => new Promise(resolve => { releaseExport = resolve; });
  const inflight = context.downloadHistoryCheckpoint();
  await Promise.resolve();
  await Promise.resolve();
  context.clearHistoryDom();
  state.page = "overview";
  state.pageVersion += 1;
  releaseExport(checkpointResponse(3));
  await inflight;
  assert.strictEqual(node("history-checkpoint-text").value, "",
    "离页后迟到的导出不得写入导出上下文");
  assert.deepStrictEqual(downloads, [], "离页后迟到的导出不得触发下载");

  // 9) F4：离页/切身份清空导出上下文
  state.page = "history";
  state.pageVersion += 1;
  node("history-checkpoint-text").value = "OLD-CHECKPOINT";
  node("history-checkpoint-status").textContent = "已导出位置 3";
  context.clearHistoryDom();
  assert.strictEqual(node("history-checkpoint-text").value, "",
    "清空历史页必须清掉已导出的检查点正文");
  assert.strictEqual(node("history-checkpoint-status").textContent, "");
  checkpointResponder = null;

  // 10) 全程不得出现业务写请求（只允许检查点核验这一个 POST）
  const writes = calls.filter(call => call[1] && (call[1].includes("/api/admin/advance")
    || call[1].includes("/api/session") || call[1].startsWith("/api/me/")));
  assert.deepStrictEqual(writes, [], "历史路径不得触碰推进/会话/人物写入口");
  const posts = calls.filter(call => call[0] === "POST")
    .filter(call => call[1] !== "/api/admin/history/verify");
  assert.deepStrictEqual(posts, [], "历史路径除核验外不得发出任何 POST");

  // 8) 清空：离开管理员视角后历史 DOM 归零
  context.clearHistoryDom();
  assert.strictEqual(node("history-position").textContent, "—");
  assert.strictEqual(node("history-index").rows.length, 0);
  assert.strictEqual(state.history.selected, null);

  console.log("HISTORY-PAGE-OK: read-only index/view, fixed position, late responses dropped");
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
