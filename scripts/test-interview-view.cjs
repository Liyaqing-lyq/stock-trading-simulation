// 采访页面身份、迟到响应和缓存清空回归；只模拟DOM原语，调用真实app.js。
// 用法：node scripts/test-interview-view.cjs [webDir] [caseFilter]
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const webDir = process.argv[2] || path.join(__dirname, "../src/main/resources/web");
const only = process.argv[3] || "";
const source = fs.readFileSync(path.join(webDir, "app.js"), "utf8");
const groupsFile = path.join(webDir, "groups.js");
const groupsSource = fs.existsSync(groupsFile) ? fs.readFileSync(groupsFile, "utf8") : "";

// ---------------------------------------------------------------- DOM 原语
function makeElement(tag = "div") {
  return {
    tag, attrs: {}, children: [], _text: "", hidden: false, value: "", dataset: {},
    get textContent() { return this._text; },
    set textContent(value) { this._text = value === null || value === undefined ? "" : String(value); },
    setAttribute(key, value) { this.attrs[key] = String(value); },
    getAttribute(key) { return Object.prototype.hasOwnProperty.call(this.attrs, key) ? this.attrs[key] : null; },
    removeAttribute(key) { delete this.attrs[key]; },
    appendChild(child) { this.children.push(child); return child; },
    append(...items) { this.children.push(...items); },
    replaceChildren(...items) { this.children = items; },
    addEventListener() { },
    querySelectorAll() { return []; },
    querySelector(selector) {
      this._qs = this._qs || new Map();
      if (!this._qs.has(selector)) this._qs.set(selector, makeElement(selector));
      return this._qs.get(selector);
    },
    classList: { add() { }, remove() { }, toggle() { } }
  };
}

function makeDocument() {
  const nodes = new Map();
  const document = {
    body: makeElement("body"),
    getElementById(id) {
      if (!nodes.has(id)) nodes.set(id, makeElement(id));
      return nodes.get(id);
    },
    createElement(tag) { return makeElement(tag); },
    createElementNS(namespace, tag) { return makeElement(tag); },
    createTextNode(text) { const node = makeElement("#text"); node.textContent = text; return node; },
    querySelectorAll() { return []; },
    addEventListener() { }
  };
  document._nodes = nodes;
  document.querySelector = () => null;
  return document;
}

// ---------------------------------------------------------------- 世界
// router(method, url, options, context) -> {status, payload} | Promise<{status, payload}>
function makeWorld(router, name) {
  const document = makeDocument();
  const calls = [];
  const context = vm.createContext({
    document, console, BigInt, Number, String, Object, Array, Map, Set, JSON,
    setTimeout, clearTimeout, setInterval, clearInterval
  });
  context.fetch = async (url, options) => {
    const method = (options && options.method) || "GET";
    calls.push(method + " " + url);
    const answer = await router(method, url, options || {}, context);
    return { status: answer.status, json: async () => answer.payload };
  };
  vm.runInContext(source, context);
  if (groupsSource) vm.runInContext(groupsSource, context);
  // `state` 是脚本级 const，不在全局对象上；显式取同一对象。
  vm.runInContext("globalThis.state = state;", context);
  const world = { name, context, document, calls, state: context.state };
  world.$ = (id) => document.getElementById(id);
  world.rows = (id) => document.getElementById(id).querySelector("tbody").children.length;
  world.cellTexts = () => {
    const out = [];
    const collect = (node) => {
      out.push(node.textContent);
      for (const child of node.children) collect(child);
    };
    for (const id of ["person-name", "person-summary", "person-accounts", "person-holdings",
      "person-market", "person-interview", "person-error"]) {
      collect(document.getElementById(id));
    }
    return out.join("|");
  };
  return world;
}

const tick = () => new Promise(resolve => setImmediate(resolve));

async function waitFor(predicate, label, limit = 400) {
  for (let index = 0; index < limit; index += 1) {
    if (predicate()) return;
    await tick();
  }
  throw new Error("等待超时：" + label);
}

// 启动期：boot() 由脚本尾部 guard(boot()) 异步启动，必须真正等它落到 login 再进用例。
async function bootSettled(world) {
  await waitFor(() => world.state.mode !== "none", "boot 完成");
  await tick();
  await tick();
  assert.equal(world.state.mode, "login", "用例前置：启动后应停在 login");
  return world;
}

// ---------------------------------------------------------------- 数据
function personView(personPartyId, extra = {}) {
  return Object.assign({
    personPartyId, timeMillis: 5000, appointments: 1, catalog: 3, readings: 2,
    observations: 4, tools: ["interview.invite", "market.read"], accounts: [],
    holdings: [], market: [], media: null
  }, extra);
}

function invite(requestId, fromPartyId, toPartyId, topic) {
  return { requestId, fromPartyId, toPartyId, topic, status: "PROPOSED",
    windowStartMillis: 1, windowEndMillis: 2, durationMillis: 3, startMillis: -1 };
}

function adminInterviewView() {
  return {
    runId: "run-1", timeMillis: 9000, position: 7,
    invites: [
      { requestId: "r1", organizationPartyId: "wire", fromPartyId: "rita", toPartyId: "investor",
        topic: "t", status: "ACTIVE", windowStartMillis: 1, windowEndMillis: 2,
        startMillis: 1, durationMillis: 3 },
      { requestId: "r2", organizationPartyId: "wire", fromPartyId: "bo", toPartyId: "rita",
        topic: "t2", status: "PROPOSED", windowStartMillis: 3, windowEndMillis: 4,
        startMillis: -1, durationMillis: 5 }
    ],
    sessions: [{ sessionId: "s1", requestId: "r1", startedAtMillis: 1, endedAtMillis: -1, close: "" }],
    works: [{ taskId: "w1", personPartyId: "rita", organizationPartyId: "wire",
      kind: "INTERVIEW_INVITE_V1", startedAtMillis: 1, completedAtMillis: 2,
      chargedMillis: 1, outcome: "COMPLETED" }],
    budgets: [{ organizationPartyId: "wire", totalMillis: 10, spentMillis: 1, reservedMillis: 3 }]
  };
}

const INTERVIEW_TABLES = ["interview-invites", "interview-sessions", "interview-works", "interview-budgets"];

// ---------------------------------------------------------------- 用例
const cases = [];
function defineCase(name, body) { cases.push({ name, body }); }

// 用例 1：延迟 GET me/interview 期间真实 showMode(login) + 切人物，旧回应不得回写、也不得清掉新身份
defineCase("stale-identity-during-interview-await", async () => {
  const pending = [];
  let sessionPerson = null;
  const views = { rita: personView("rita"), bob: personView("bob", { timeMillis: 6000 }) };
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: views[sessionPerson] };
    if (url === "/api/me/interview") {
      return new Promise(resolve => pending.push({ person: sessionPerson, resolve }));
    }
    return { status: 200, payload: {} };
  }, "case1");
  await bootSettled(world);

  const first = world.context.switchPerson("rita");
  await waitFor(() => pending.length === 1, "rita 的采访请求已发出");
  assert.equal(pending[0].person, "rita", "第一次请求必须属于 rita");

  // 真实切身份入口：先 showMode("login")（登出/离页路径），再以新身份进入
  world.context.showMode("login");
  assert.equal(world.state.mode, "login", "showMode(login) 必须真的切到 login");
  assert.equal(world.$("person-name").textContent, "—", "切身份必须清掉旧人物标记");
  assert.equal(world.$("person-interview").hidden, true, "切身份必须隐藏个人采访面板");

  const second = world.context.switchPerson("bob");
  await waitFor(() => pending.length === 2, "bob 的采访请求已发出");
  assert.equal(pending[1].person, "bob", "第二次请求必须属于 bob");
  pending[1].resolve({ status: 200, payload: { personPartyId: "bob",
    invites: [invite("bob-1", "bob", "investor", "新身份主题")], sessions: [], contacts: [] } });
  await second;
  assert.equal(world.$("person-name").textContent, "bob", "新身份必须写入自己的标记");
  assert.equal(world.rows("person-interview-invites"), 1, "新身份应渲染自己的 1 条预约");

  // 现在才释放旧身份结果
  pending[0].resolve({ status: 200, payload: { personPartyId: "rita",
    invites: [invite("rita-1", "rita", "investor", "旧身份主题"),
      invite("rita-2", "rita", "wire", "旧身份主题二")],
    sessions: [], contacts: [{ targetPartyId: "investor", channel: "c", description: "d" }] } });
  await first;

  assert.equal(world.$("person-name").textContent, "bob", "迟到的旧回应不得覆盖新人物标记");
  assert.equal(world.rows("person-interview-invites"), 1, "迟到的旧回应不得写入旧预约");
  assert.equal(world.rows("person-interview-contacts"), 0, "迟到的旧回应不得写入旧联系入口");
  assert.ok(!world.cellTexts().includes("rita"), "个人视图不得出现旧身份文本，实际=" + world.cellTexts());
  assert.equal(world.state.mode, "person", "最终仍在人物视角");
  return "bob 数据完整、rita 迟到回应未回写";
});

// 用例 2：预约请求 401 触发 sessionLost，且旧 view 不再写回
defineCase("interview-401-triggers-sessionLost", async () => {
  let sessionPerson = null;
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("rita") };
    if (url === "/api/me/interview") return { status: 401, payload: null };
    return { status: 200, payload: {} };
  }, "case2");
  await bootSettled(world);

  await world.context.switchPerson("rita");
  assert.equal(world.state.mode, "login", "采访 401 必须走 sessionLost 进入 login");
  assert.equal(world.$("person-name").textContent, "—", "401 后不得留下人物标记");
  assert.equal(world.rows("person-accounts"), 0, "401 后旧 view 不得写回账户表");
  assert.equal(world.rows("person-holdings"), 0, "401 后旧 view 不得写回持仓表");
  assert.ok((world.$("login-error").textContent || "").includes("会话已失效"),
    "应给出会话失效提示，实际=" + world.$("login-error").textContent);
  return "401 → login，旧 view 未写回";
});

// 用例 3：管理员四表 + cache 渲染后，logout / 切人物 / 离页都必须清空 DOM 与 cache
function baseRouter() {
  return async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/admin/interview") return { status: 200, payload: adminInterviewView() };
    if (url === "/api/admin/keys") return { status: 200, payload: { slots: [] } };
    if (url === "/api/admin/routes") return { status: 200, payload: { revision: 1, baseUrl: "x", connected: false, routes: [] } };
    if (url === "/api/admin/capabilities") return { status: 200, payload: { supportedScenes: [] } };
    if (url === "/api/admin/view") return { status: 200, payload: {} };
    if (url === "/api/session/logout") return { status: 200, payload: {} };
    if (url === "/api/session/person") return { status: 200, payload: { personId: JSON.parse(options.body).personId } };
    if (url === "/api/me/view") return { status: 200, payload: personView("bob") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "bob", invites: [], sessions: [], contacts: [] } };
    return { status: 200, payload: {} };
  };
}

async function adminRendered() {
  const world = makeWorld(baseRouter(), "case3");
  await bootSettled(world);
  world.context.showMode("admin");
  world.state.page = "interview";
  await world.context.loadDetail("interview");
  assert.ok(world.state.details.interview, "前置：采访详情必须进入 cache");
  for (const id of INTERVIEW_TABLES) {
    assert.ok(world.rows(id) > 0, "前置：" + id + " 必须已渲染");
  }
  assert.equal(world.$("interview-invites-empty").textContent, "", "前置：有数据时提示为空");
  return world;
}

function assertInterviewCleared(world, how) {
  for (const id of INTERVIEW_TABLES) {
    assert.equal(world.rows(id), 0, how + " 后必须清空 " + id + " 行");
  }
  for (const id of ["interview-invites-empty", "interview-sessions-empty", "interview-works-empty"]) {
    assert.equal(world.$(id).textContent, "", how + " 后必须清空 " + id + " 文案");
  }
  assert.ok(!Object.prototype.hasOwnProperty.call(world.state.details, "interview"),
    how + " 后必须删除采访 cache，实际 keys=" + JSON.stringify(Object.keys(world.state.details)));
}

defineCase("admin-interview-cleared-on-logout", async () => {
  const world = await adminRendered();
  await world.context.logout();
  assert.equal(world.state.mode, "login", "logout 应停在 login");
  assertInterviewCleared(world, "logout");
  return "logout 清空四表、提示文案与 cache";
});

defineCase("admin-interview-cleared-on-switch-person", async () => {
  const world = await adminRendered();
  await world.context.switchPerson("bob");
  assert.equal(world.state.mode, "person", "切人物后应进入人物视角");
  assertInterviewCleared(world, "切人物");
  return "切人物清空四表、提示文案与 cache";
});

defineCase("admin-interview-cleared-on-leave-page", async () => {
  const world = await adminRendered();
  world.context.selectPage("overview", false);
  assert.equal(world.state.page, "overview", "应已离页");
  assertInterviewCleared(world, "离页");
  return "离页清空四表、提示文案与 cache";
});

// 用例 4：个人预约/contacts 切身份即清空；旧延迟失败不得影响新结果
defineCase("personal-interview-cleared-on-identity-switch", async () => {
  const pending = [];
  let sessionPerson = null;
  const views = { rita: personView("rita"), bob: personView("bob") };
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: views[sessionPerson] };
    if (url === "/api/me/interview") {
      if (sessionPerson === "rita") {
        return { status: 200, payload: { personPartyId: "rita",
          invites: [invite("rita-1", "rita", "investor", "旧")],
          sessions: [], contacts: [{ targetPartyId: "investor", channel: "c", description: "d" }] } };
      }
      return new Promise((resolve, reject) => pending.push({ reject }));
    }
    return { status: 200, payload: {} };
  }, "case4");
  await bootSettled(world);

  await world.context.switchPerson("rita");
  assert.equal(world.rows("person-interview-invites"), 1, "前置：rita 预约已渲染");
  assert.equal(world.rows("person-interview-contacts"), 1, "前置：rita 联系入口已渲染");

  const second = world.context.switchPerson("bob");
  await waitFor(() => pending.length === 1, "bob 的采访请求已发出");
  assert.equal(world.$("person-name").textContent, "bob", "前置：已切到 bob 标记");
  const duringInvites = world.rows("person-interview-invites");
  const duringContacts = world.rows("person-interview-contacts");
  const duringHidden = world.$("person-interview").hidden;

  pending[0].reject(new Error("network down"));
  await second;
  const afterInvites = world.rows("person-interview-invites");
  const afterContacts = world.rows("person-interview-contacts");
  const afterHidden = world.$("person-interview").hidden;
  console.log("  诊断 切身份等待期 rows=" + duringInvites + "/" + duringContacts
    + " hidden=" + duringHidden + " → 新请求失败后 rows=" + afterInvites + "/" + afterContacts
    + " hidden=" + afterHidden + " 文本=" + JSON.stringify(world.cellTexts()));

  assert.equal(duringInvites, 0, "切身份后必须立刻清空旧预约行（等待期仍残留 " + duringInvites + " 行）");
  assert.equal(duringContacts, 0, "切身份后必须立刻清空旧联系入口行");
  assert.equal(world.$("person-name").textContent, "bob", "新身份标记必须保留");
  assert.equal(world.rows("person-interview-invites"), 0, "旧延迟失败不得让旧预约复活");
  assert.equal(world.rows("person-interview-contacts"), 0, "旧延迟失败不得让旧联系入口复活");
  assert.equal(world.$("person-interview").hidden, true, "无预约时应隐藏个人采访面板");
  assert.ok(!world.cellTexts().includes("旧") && !world.cellTexts().includes("investor"),
    "不得出现旧身份数据，实际=" + world.cellTexts());
  return "切身份立即清空；旧延迟失败未污染新身份";
});

// 用例 5：离页（person→login→person）时旧延迟失败同样不得污染新身份（真实登出中转）
defineCase("personal-interview-stale-failure-after-logout", async () => {
  const pending = [];
  let sessionPerson = null;
  const views = { rita: personView("rita"), bob: personView("bob") };
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: views[sessionPerson] };
    if (url === "/api/me/interview") {
      if (sessionPerson === "rita") {
        return new Promise((resolve, reject) => pending.push({ reject }));
      }
      return { status: 200, payload: { personPartyId: "bob",
        invites: [invite("bob-1", "bob", "wire", "bob 主题")], sessions: [], contacts: [] } };
    }
    return { status: 200, payload: {} };
  }, "case5");
  await bootSettled(world);

  const first = world.context.switchPerson("rita");
  await waitFor(() => pending.length === 1, "rita 的采访请求已发出");
  world.context.showMode("login");
  await world.context.switchPerson("bob");
  assert.equal(world.$("person-name").textContent, "bob", "前置：bob 已渲染");
  assert.equal(world.rows("person-interview-invites"), 1, "前置：bob 预约已渲染");

  pending[0].reject(new Error("network down"));
  await first;
  assert.equal(world.$("person-name").textContent, "bob", "旧失败不得覆盖新标记");
  assert.equal(world.rows("person-interview-invites"), 1, "旧失败不得清掉新身份预约");
  assert.ok(!world.cellTexts().includes("rita"), "不得出现旧身份文本");
  return "旧请求失败后 bob 数据保持";
});

// 用例 6（候选-2 新增）：详情页没有 runId 时只显示真实时点，不得出现 undefined
defineCase("admin-detail-without-runid-shows-time-only", async () => {
  const view = adminInterviewView();
  delete view.runId;
  const world = makeWorld(async (method, url) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/admin/interview") return { status: 200, payload: view };
    return { status: 200, payload: {} };
  }, "case6");
  await bootSettled(world);
  world.context.showMode("admin");
  world.state.page = "interview";
  await world.context.loadDetail("interview");
  const shown = world.$("context-time").textContent;
  assert.ok(!String(shown).includes("undefined"), "详情页无 runId 时不得显示 undefined，实际=" + JSON.stringify(shown));
  assert.equal(shown, "9000 毫秒", "应只显示真实时点，实际=" + JSON.stringify(shown));
  assert.ok(world.rows("interview-invites") > 0, "同一渲染里四表仍应正常");
  return "无 runId → context-time=" + JSON.stringify(shown);
});

// 用例 7（候选-2 新增）：个人采访动作必须取 media.tools（真实 me/view 的 tools 只有 information 三项）
defineCase("personal-interview-tools-from-media", async () => {
  let sessionPerson = null;
  const media = {
    tools: ["interview.respond", "interview.cancel", "interview.end"],
    stories: [], reports: []
  };
  const view = personView("investor", {
    tools: ["information.read", "information.recall", "information.belief"], media
  });
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: view };
    if (url === "/api/me/interview") {
      return { status: 200, payload: { personPartyId: "investor", invites: [], sessions: [], contacts: [] } };
    }
    return { status: 200, payload: {} };
  }, "case7");
  await bootSettled(world);
  await world.context.switchPerson("investor");

  const tools = world.$("person-interview-tools").textContent || "";
  for (const action of media.tools) {
    assert.ok(tools.includes(action), "采访动作必须显示 " + action + "，实际=" + JSON.stringify(tools));
  }
  assert.ok(!tools.includes("information."), "不得把 information 工具当成采访动作，实际=" + JSON.stringify(tools));
  assert.equal(world.$("person-interview").hidden, false,
    "只有采访动作、无预约无联系人时面板也应显示（actions 非空）");
  return "tools=" + JSON.stringify(tools) + " 面板可见";
});

// ---------------------------------------------------------------- 逐轮访谈（schema15）
function dialogueSection(rows) {
  return { total: rows.length, rows };
}

function adminDialogueView() {
  return {
    runId: "run-1", position: "12", timeMillis: "9000", available: true,
    sessions: dialogueSection([{ sessionId: "SESSION-4:rita:q1", requestId: "4:rita:q1",
      organizationPartyId: "wire", startedAtMillis: "1000", endedAtMillis: "9000", open: false }]),
    turns: dialogueSection([
      { sessionId: "SESSION-4:rita:q1", sequence: "1", turnId: "t1", speakerPartyId: "rita",
        peerPartyId: "investor", docId: "dialogue-turn:17:SESSION-4:rita:q1:1", docVersion: "1",
        publishedAtMillis: "2000", speakerRead: false, peerRead: true, title: "Q1", body: "问题" },
      { sessionId: "SESSION-4:rita:q1", sequence: "2", turnId: "t2", speakerPartyId: "investor",
        peerPartyId: "rita", docId: "dialogue-turn:17:SESSION-4:rita:q1:2", docVersion: "1",
        publishedAtMillis: "3000", speakerRead: false, peerRead: false, title: "A1", body: "回答" }
    ]),
    closes: dialogueSection([{ sessionId: "SESSION-4:rita:q1", reason: "CLOSED_BY_PARTY" }]),
    reports: dialogueSection([{ taskId: "task-1", personPartyId: "rita",
      kind: "INTERVIEW_INVITE_V1", accepted: true, reason: "OK", requestId: "4:rita:q1",
      timeMillis: "1500" }]),
    notes: ["测试备注"]
  };
}

defineCase("admin-dialogue-rendered-and-cleared", async () => {
  let dialogue = adminDialogueView();
  const world = makeWorld(async (method, url) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/admin/interview") return { status: 200, payload: adminInterviewView() };
    if (url === "/api/admin/dialogue") return { status: 200, payload: dialogue };
    if (url === "/api/session/logout") return { status: 200, payload: {} };
    return { status: 200, payload: {} };
  }, "dialogue-admin");
  await bootSettled(world);
  world.context.showMode("admin");
  world.state.page = "interview";
  await world.context.loadDetail("interview");

  assert.equal(world.rows("dialogue-turns"), 2, "上帝审计必须渲染真实轮次");
  assert.equal(world.rows("dialogue-closes"), 1, "关闭原因必须渲染");
  assert.equal(world.rows("dialogue-reports"), 1, "采访动作回执必须渲染");
  assert.ok(world.state.dialogue, "审计结果必须进入 cache");
  assert.ok(world.$("dialogue-note").textContent.includes("测试备注"), "备注来自服务器");

  await world.context.logout();
  for (const id of ["dialogue-turns", "dialogue-closes", "dialogue-reports", "dialogue-sessions"]) {
    assert.equal(world.rows(id), 0, "登出必须清空 " + id);
  }
  assert.equal(world.state.dialogue, null, "登出必须清空对话 cache");
  assert.equal(world.$("dialogue-note").textContent, "", "登出必须清空说明文案");

  // 重新渲染后离页也要清空
  world.context.showMode("admin");
  world.state.page = "interview";
  dialogue = adminDialogueView();
  await world.context.loadDetail("interview");
  assert.equal(world.rows("dialogue-turns"), 2, "前置：重新渲染");
  world.context.selectPage("overview", false);
  assert.equal(world.rows("dialogue-turns"), 0, "离页必须清空轮次表");
  assert.equal(world.state.dialogue, null, "离页必须清空对话 cache");
  return "上帝审计渲染并在登出/离页清空";
});

function personDialogueView() {
  return {
    personPartyId: "rita", timeMillis: "9000", available: true,
    sessions: dialogueSection([{ sessionId: "SESSION-4:rita:q1", requestId: "4:rita:q1",
      peerPartyId: "investor", startedAtMillis: "1000", endedAtMillis: "9000", open: false,
      closeReason: "CLOSED_BY_PARTY" }]),
    turns: dialogueSection([
      { sessionId: "SESSION-4:rita:q1", sequence: "1", turnId: "t1", speakerPartyId: "rita",
        mine: true, docId: "dialogue-turn:17:SESSION-4:rita:q1:1", docVersion: "1",
        readByMe: false, readByPeer: true, publishedAtMillis: "2000", title: "", body: "" },
      { sessionId: "SESSION-4:rita:q1", sequence: "2", turnId: "t2", speakerPartyId: "investor",
        mine: false, docId: "dialogue-turn:17:SESSION-4:rita:q1:2", docVersion: "1",
        readByMe: true, readByPeer: false, publishedAtMillis: "3000", title: "A1", body: "回答正文" }
    ]),
    notes: ["个人备注"]
  };
}

function rowCells(world, tableId, index) {
  const body = world.$(tableId).querySelector("tbody");
  return (body.children[index] || { children: [] }).children.map(cell => cell.textContent);
}

defineCase("person-dialogue-hides-unread-body", async () => {
  let sessionPerson = null;
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("rita") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "rita",
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") return { status: 200, payload: personDialogueView() };
    return { status: 200, payload: {} };
  }, "dialogue-person");
  await bootSettled(world);
  await world.context.switchPerson("rita");

  assert.equal(world.rows("person-dialogue-turns"), 2, "本人轮次必须渲染");
  const own = rowCells(world, "person-dialogue-turns", 0);
  const peer = rowCells(world, "person-dialogue-turns", 1);
  assert.ok(!own.includes("回答正文"), "未读轮次不得显示正文");
  assert.ok(!own.includes("A1"), "未读轮次不得显示标题");
  assert.ok(peer.includes("回答正文"), "已读轮次显示正文");
  assert.ok(peer.includes("A1"), "已读轮次显示标题");
  assert.equal(world.rows("person-dialogue-sessions"), 1, "本人会话表渲染");

  world.context.showMode("login");
  assert.equal(world.rows("person-dialogue-turns"), 0, "切身份必须清空本人轮次");
  assert.equal(world.rows("person-dialogue-sessions"), 0, "切身份必须清空本人会话");
  assert.equal(world.state.personDialogue, null, "切身份必须清空本人对话 cache");
  return "未读正文不展示，切身份清空";
});

defineCase("person-dialogue-late-response-discarded", async () => {
  let release = null;
  let sessionPerson = null;
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("rita") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "rita",
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") {
      return new Promise(resolve => { release = resolve; });
    }
    return { status: 200, payload: {} };
  }, "dialogue-late");
  await bootSettled(world);
  const running = world.context.switchPerson("rita");
  await waitFor(() => release !== null, "对话请求已发出");
  world.context.showMode("login");
  release({ status: 200, payload: personDialogueView() });
  await running;
  assert.equal(world.rows("person-dialogue-turns"), 0, "迟到回应不得写入已切走的身份");
  assert.equal(world.state.personDialogue, null, "迟到回应不得写回 cache");
  return "代次变化后迟到回应被丢弃";
});

// 独立复核补充用例：未读正文的前端掩蔽、离页/切身份迟到响应、失败不污染。
// 追加在作者 12 条断言之后，不改原用例；用法同 test-interview-view.cjs。
"use strict";

// 用例 A：服务器泄漏未读正文时，前端仍不得显示（判别 renderPersonalDialogue 的 readByMe 掩蔽）
defineCase("person-dialogue-masks-unread-body-if-server-leaks", async () => {
  let sessionPerson = null;
  const leaked = {
    personPartyId: "rita", timeMillis: "9000", available: true,
    sessions: dialogueSection([{ sessionId: "SESSION-4:rita:q1", requestId: "4:rita:q1",
      peerPartyId: "investor", startedAtMillis: "1000", endedAtMillis: "9000", open: false,
      closeReason: "CLOSED_BY_PARTY" }]),
    turns: dialogueSection([
      { sessionId: "SESSION-4:rita:q1", sequence: "1", turnId: "t1", speakerPartyId: "rita",
        mine: true, docId: "dialogue-turn:17:SESSION-4:rita:q1:1", docVersion: "1",
        readByMe: false, readByPeer: true, publishedAtMillis: "2000",
        title: "泄漏标题", body: "泄漏正文" },
      { sessionId: "SESSION-4:rita:q1", sequence: "2", turnId: "t2", speakerPartyId: "investor",
        mine: false, docId: "dialogue-turn:17:SESSION-4:rita:q1:2", docVersion: "1",
        readByMe: true, readByPeer: false, publishedAtMillis: "3000", title: "A1", body: "回答正文" }
    ]),
    notes: []
  };
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("rita") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "rita",
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") return { status: 200, payload: leaked };
    return { status: 200, payload: {} };
  }, "indep-leak");
  await bootSettled(world);
  await world.context.switchPerson("rita");

  assert.equal(world.rows("person-dialogue-turns"), 2, "两条轮次都要渲染行");
  const own = rowCells(world, "person-dialogue-turns", 0);
  const peer = rowCells(world, "person-dialogue-turns", 1);
  assert.ok(!own.includes("泄漏正文"), "服务器泄漏的未读正文不得上屏，实际=" + JSON.stringify(own));
  assert.ok(!own.includes("泄漏标题"), "服务器泄漏的未读标题不得上屏，实际=" + JSON.stringify(own));
  assert.ok(own.includes("未读"), "未读轮次仍要标出未读");
  assert.ok(peer.includes("回答正文") && peer.includes("A1"), "已读轮次照常显示正文");
  return "前端按 readByMe 自行掩蔽未读正文/标题";
});

// 用例 B：管理员离页后迟到的 /api/admin/dialogue 响应不得回写（判别 page/pageVersion 守卫）
defineCase("admin-dialogue-late-after-leave-page-discarded", async () => {
  let release = null;
  const world = makeWorld(async (method, url) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/admin/interview") return { status: 200, payload: adminInterviewView() };
    if (url === "/api/admin/dialogue") return new Promise(resolve => { release = resolve; });
    return { status: 200, payload: {} };
  }, "indep-admin-late");
  await bootSettled(world);
  world.context.showMode("admin");
  world.state.page = "interview";
  const loading = world.context.loadDetail("interview");
  await waitFor(() => release !== null, "对话审计请求已发出");

  world.context.selectPage("overview", false);
  assert.equal(world.state.page, "overview", "前置：已离页");
  release({ status: 200, payload: adminDialogueView() });
  await loading;
  await tick();
  for (const id of ["dialogue-sessions", "dialogue-turns", "dialogue-closes", "dialogue-reports"]) {
    assert.equal(world.rows(id), 0, "离页后迟到回应不得写入 " + id);
  }
  assert.equal(world.state.dialogue, null, "离页后迟到回应不得写回 cache");
  return "离页后迟到审计响应被丢弃";
});

// 用例 C：切到新身份并渲染完成后，旧身份的迟到对话响应不得覆盖（判别代次守卫本身）
defineCase("person-dialogue-late-after-identity-switch-discarded", async () => {
  let sessionPerson = null;
  let releaseRita = null;
  const bobView = {
    personPartyId: "bob", timeMillis: "9000", available: true,
    sessions: dialogueSection([{ sessionId: "SESSION-9:bob:q2", requestId: "9:bob:q2",
      peerPartyId: "investor", startedAtMillis: "1000", endedAtMillis: "9000", open: true,
      closeReason: "" }]),
    turns: dialogueSection([{ sessionId: "SESSION-9:bob:q2", sequence: "1", turnId: "b1",
      speakerPartyId: "bob", mine: true, docId: "dialogue-turn:17:SESSION-9:bob:q2:1",
      docVersion: "1", readByMe: true, readByPeer: false, publishedAtMillis: "2000",
      title: "B1", body: "bob 正文" }]),
    notes: []
  };
  const views = { rita: personView("rita"), bob: personView("bob") };
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: views[sessionPerson] };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: sessionPerson,
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") {
      if (sessionPerson === "rita") return new Promise(resolve => { releaseRita = resolve; });
      return { status: 200, payload: bobView };
    }
    return { status: 200, payload: {} };
  }, "indep-switch-late");
  await bootSettled(world);

  const first = world.context.switchPerson("rita");
  await waitFor(() => releaseRita !== null, "rita 的对话请求已发出");
  await world.context.switchPerson("bob");
  assert.equal(world.rows("person-dialogue-turns"), 1, "前置：bob 的对话已渲染");
  const before = world.cellTexts();

  releaseRita({ status: 200, payload: personDialogueView() });
  await first;
  assert.equal(world.state.mode, "person", "仍在人物视角");
  assert.equal(world.state.personDialogue && world.state.personDialogue.personPartyId, "bob",
    "cache 必须仍是 bob，实际=" + JSON.stringify(world.state.personDialogue && world.state.personDialogue.personPartyId));
  assert.equal(world.rows("person-dialogue-turns"), 1, "rita 的 2 条轮次不得覆盖 bob 的 1 条");
  assert.deepEqual(world.cellTexts(), before, "旧身份迟到响应不得改动任何单元格");
  return "同 mode 下代次变化，rita 迟到响应被丢弃";
});

// 用例 D：本人对话接口失败时不得留下脏行、不得抛错，且不影响后续成功渲染
defineCase("person-dialogue-failure-keeps-panel-clean", async () => {
  let sessionPerson = null;
  let fail = true;
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("rita") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "rita",
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") {
      return fail ? { status: 500, payload: { error: "WORLD_IO" } }
        : { status: 200, payload: personDialogueView() };
    }
    return { status: 200, payload: {} };
  }, "indep-failure");
  await bootSettled(world);
  await world.context.switchPerson("rita");
  assert.equal(world.rows("person-dialogue-turns"), 0, "失败时不得渲染轮次");
  assert.equal(world.rows("person-dialogue-sessions"), 0, "失败时不得渲染会话");
  assert.equal(world.state.personDialogue, null, "失败时不得写 cache");
  assert.equal(world.state.mode, "person", "500 不等于会话失效，不得跳登录");
  assert.equal(world.$("person-name").textContent, "rita", "失败不得清掉身份标记");

  fail = false;
  world.context.showMode("login");
  await world.context.switchPerson("rita");
  assert.equal(world.rows("person-dialogue-turns"), 2, "恢复后应正常渲染");
  return "500 不渲染不跳转，恢复后正常";
});

// ---------------------------------------------------------------- 证据与共享（schema16）
function evidenceSection(rows) {
  return { total: rows.length, rows };
}

function adminEvidenceView() {
  return {
    runId: "run-1", position: "20", timeMillis: "9000", available: true,
    roots: evidenceSection([{ organizationPartyId: "wire", docId: "doc:private", version: "1",
      aliasCount: "1", shareCount: "1" }]),
    aliases: evidenceSection([{ organizationPartyId: "wire", storyId: "m:4:rita:story-1",
      revisionNo: "1", docId: "doc:private", version: "1", publicLabel: "受访者A",
      publicDocId: "story-public-1", publicVersion: "1" }]),
    shares: evidenceSection([{ organizationPartyId: "wire", shareId: "share-1",
      docId: "doc:private", version: "1", recipientPartyId: "investor",
      sharingDocId: "share-doc-1", sharingVersion: "1", recipientRead: false }]),
    notes: ["证据备注"]
  };
}

function personEvidenceView(extra = {}) {
  return Object.assign({
    personPartyId: "rita", timeMillis: "9000", available: true,
    labels: evidenceSection([{ storyId: "m:4:rita:story-1", publicDocId: "story-public-1",
      publicVersion: "1", labelledRootDocId: "doc:private", publicLabel: "受访者A" }]),
    shares: evidenceSection([{ shareId: "share-1", sharingDocId: "share-doc-1",
      sharingVersion: "1", sharedRootDocId: "doc:private", sharedRootVersion: "1",
      title: "来源说明", body: "这是共享说明" }]),
    notes: ["个人证据备注"]
  }, extra);
}

defineCase("admin-evidence-rendered-and-cleared", async () => {
  const world = makeWorld(async (method, url) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/admin/view") return { status: 200, payload: {
      runId: "run-1", timeMillis: 9000, position: 20, phase: "IDLE", paused: false,
      providerKind: "SCRIPTED", barrierPending: false, pendingCount: 0, deliveredCount: 0,
      accounts: [], owners: [], pending: [], trades: [], dividendReports: [],
      valuationsAvailable: true } };
    if (url === "/api/admin/keys") return { status: 200, payload: { slots: [] } };
    if (url === "/api/admin/routes") return { status: 200,
      payload: { revision: 1, baseUrl: "x", connected: false, routes: [] } };
    if (url === "/api/admin/capabilities") return { status: 200, payload: {
      supportedScenes: [], persons: [], mediaAvailable: false, position: "20",
      timeMillis: "9000" } };
    if (url === "/api/admin/evidence") return { status: 200, payload: adminEvidenceView() };
    if (url === "/api/session/logout") return { status: 200, payload: {} };
    return { status: 200, payload: {} };
  }, "evidence-admin");
  await bootSettled(world);
  world.context.showMode("admin");
  world.context.selectPage("evidence", true);
  try {
    await waitFor(() => world.rows("evidence-roots") === 1, "证据页渲染");
  } catch (failure) {
    throw new Error("证据页未渲染，calls=" + JSON.stringify(world.calls)
      + " page=" + world.state.page + " cache=" + JSON.stringify(world.state.evidence));
  }

  assert.equal(world.rows("evidence-aliases"), 1, "别名表必须渲染");
  assert.equal(world.rows("evidence-shares"), 1, "共享表必须渲染");
  assert.equal(world.rows("evidence-shares") >= 1
    && world.$("evidence-shares").querySelector("tbody").children[0]
      .children[7].textContent, "未读", "共享记录必须显示接收者未读");
  assert.ok(world.$("evidence-note").textContent.includes("证据备注"), "备注来自服务器");

  world.context.selectPage("overview", false);
  for (const id of ["evidence-roots", "evidence-aliases", "evidence-shares"]) {
    assert.equal(world.rows(id), 0, "离页必须清空 " + id);
  }
  assert.equal(world.state.evidence, null, "离页必须清空证据 cache");

  world.context.selectPage("evidence", false);
  await world.context.loadEvidence(world.state.generation, world.state.pageVersion);
  assert.equal(world.rows("evidence-roots"), 1, "前置：重新渲染");
  await world.context.logout();
  assert.equal(world.rows("evidence-roots"), 0, "登出必须清空证据根");
  assert.equal(world.state.evidence, null, "登出必须清空证据 cache");
  return "证据页渲染并在离页/登出清空";
});

defineCase("person-evidence-shows-only-read-share", async () => {
  let sessionPerson = null;
  let showEvidence = true;
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      sessionPerson = JSON.parse(options.body).personId;
      return { status: 200, payload: { personId: sessionPerson } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("rita") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "rita",
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") return { status: 200, payload: { personPartyId: "rita",
      timeMillis: "9000", available: true, sessions: evidenceSection([]),
      turns: evidenceSection([]), notes: [] } };
    if (url === "/api/me/evidence") return { status: 200, payload: showEvidence
      ? personEvidenceView() : personEvidenceView({ labels: evidenceSection([]), shares: evidenceSection([]) }) };
    return { status: 200, payload: {} };
  }, "evidence-person");
  await bootSettled(world);
  await world.context.switchPerson("rita");

  assert.equal(world.rows("person-evidence-shares"), 1, "本人已读共享必须渲染");
  assert.equal(world.rows("person-evidence-labels"), 1, "本人已读公开标签必须渲染");
  const shareCells = world.$("person-evidence-shares").querySelector("tbody").children[0]
    .children.map(cell => cell.textContent);
  assert.ok(shareCells.includes("来源说明"), "共享必须显示说明标题，实际=" + shareCells);
  assert.ok(shareCells.includes("这是共享说明"), "必须显示实际读到的说明正文");
  assert.ok(!shareCells.includes("doc:private"), "说明不额外披露后台私证编号");

  showEvidence = false;
  await world.context.renderPersonalEvidence(world.state.generation);
  assert.equal(world.rows("person-evidence-shares"), 0, "后来变空时清空共享");
  assert.equal(world.rows("person-evidence-labels"), 0, "后来变空时清空标签");
  assert.equal(world.$("person-evidence").hidden, true, "后来变空时隐藏面板");

  showEvidence = true;
  await world.context.renderPersonalEvidence(world.state.generation);
  assert.equal(world.rows("person-evidence-shares"), 1, "切身份前恢复非空状态");

  world.context.showMode("login");
  assert.equal(world.rows("person-evidence-shares"), 0, "切身份必须清空本人共享");
  assert.equal(world.rows("person-evidence-labels"), 0, "切身份必须清空本人标签");
  assert.equal(world.state.personEvidence, null, "切身份必须清空本人证据 cache");
  return "只显示已读共享与标签，切身份清空";
});

defineCase("person-evidence-empty-hides-panel", async () => {
  const empty = personEvidenceView({ labels: evidenceSection([]),
    shares: evidenceSection([]) });
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      return { status: 200,
        payload: { personId: JSON.parse(options.body).personId } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("rita") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "rita",
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") return { status: 200, payload: { personPartyId: "rita",
      timeMillis: "9000", available: true, sessions: evidenceSection([]),
      turns: evidenceSection([]), notes: [] } };
    if (url === "/api/me/evidence") return { status: 200, payload: empty };
    return { status: 200, payload: {} };
  }, "evidence-empty");
  await bootSettled(world);
  await world.context.switchPerson("rita");
  assert.equal(world.rows("person-evidence-shares"), 0, "没有共享时不得有空行");
  assert.equal(world.$("person-evidence").hidden, true, "没有内容时不得显示证据面板");
  return "无共享时不出行也不显示面板";
});

// ---------------------------------------------------------------- 消息（schema17）
function socialSection(rows) {
  return { total: rows.length, rows };
}

function adminSocialView() {
  return {
    runId: "run-1", position: "20", timeMillis: "9000", available: true,
    messages: socialSection([
      { messageId: "m1", from: "nina", to: "omar", channel: "私信", status: "DELIVERED",
        title: "约个时间", sentAt: "1", dueAt: "2", statusAt: "2", readByReceiver: true,
        rejectReason: "" },
      { messageId: "m2", from: "nina", to: "omar", channel: "私信", status: "BLOCKED",
        title: "第二封", sentAt: "3", dueAt: "4", statusAt: "4", readByReceiver: false,
        rejectReason: "BLOCKED" }
    ]),
    blocks: socialSection([{ ownerPartyId: "omar", targetPartyId: "nina", since: "5",
      commandId: "c1" }]),
    reports: socialSection([{ taskId: "t1", person: "nina", kind: "SOCIAL_DELIVER_V1",
      accepted: true, reason: "DELIVERED", messageId: "m1", atMillis: "2" }]),
    contacts: socialSection([{ targetPartyId: "nina", channel: "私信", description: "d",
      docId: "doc", version: "1" }]),
    notes: ["消息备注"]
  };
}

function personSocialView(extra = {}) {
  return Object.assign({
    personPartyId: "omar", timeMillis: "9000", available: true,
    inbox: socialSection([{ messageId: "m1", from: "nina", channel: "私信", title: "约个时间",
      docId: "doc", version: "1", deliveredAt: "2", read: true, body: "<b>x</b> 正文" },
      { messageId: "m2", from: "nina", channel: "私信", title: "第二封",
        docId: "doc2", version: "1", deliveredAt: "4", read: false, body: "" }]),
    outbox: socialSection([{ messageId: "m3", to: "nina", channel: "私信", sentAt: "6",
      dueAt: "7", status: "UNDELIVERED" }]),
    contacts: socialSection([{ targetPartyId: "nina", channel: "私信", description: "d",
      docId: "doc", version: "1" }]),
    notes: ["个人消息备注"]
  }, extra);
}

defineCase("admin-social-rendered-and-cleared", async () => {
  const world = makeWorld(async (method, url) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/admin/view") return { status: 200, payload: {
      runId: "run-1", timeMillis: 9000, position: 20, phase: "IDLE", paused: false,
      providerKind: "SCRIPTED", barrierPending: false, pendingCount: 0, deliveredCount: 0,
      accounts: [], owners: [], pending: [], trades: [], dividendReports: [],
      valuationsAvailable: true } };
    if (url === "/api/admin/keys") return { status: 200, payload: { slots: [] } };
    if (url === "/api/admin/routes") return { status: 200,
      payload: { revision: 1, baseUrl: "x", connected: false, routes: [] } };
    if (url === "/api/admin/capabilities") return { status: 200, payload: {
      supportedScenes: [], persons: [], mediaAvailable: false, position: "20",
      timeMillis: "9000" } };
    if (url === "/api/admin/social") return { status: 200, payload: adminSocialView() };
    if (url === "/api/session/logout") return { status: 200, payload: {} };
    return { status: 200, payload: {} };
  }, "social-admin");
  await bootSettled(world);
  world.context.showMode("admin");
  world.context.selectPage("social", true);
  await waitFor(() => world.rows("social-messages") === 2, "消息页渲染");

  const first = rowCells(world, "social-messages", 0);
  const second = rowCells(world, "social-messages", 1);
  assert.ok(first.includes("已送达"), "状态必须用中文口径，实际=" + first);
  assert.ok(first.includes("已读"), "收件人已读必须显示");
  assert.ok(second.includes("未送达"), "BLOCKED 对外必须显示未送达，实际=" + second);
  assert.ok(second.includes("BLOCKED"), "管理员可看真实原因");
  assert.equal(world.rows("social-blocks"), 1, "屏蔽记录必须渲染");
  assert.equal(world.rows("social-reports"), 1, "报告必须渲染");
  assert.ok(world.$("social-note").textContent.includes("消息备注"), "备注来自服务器");

  world.context.selectPage("overview", false);
  for (const id of ["social-messages", "social-blocks", "social-reports", "social-contacts"]) {
    assert.equal(world.rows(id), 0, "离页必须清空 " + id);
  }
  assert.equal(world.state.social, null, "离页必须清空消息 cache");

  world.context.selectPage("social", false);
  await world.context.loadSocial(world.state.generation, world.state.pageVersion);
  assert.equal(world.rows("social-messages"), 2, "前置：重新渲染");
  await world.context.logout();
  assert.equal(world.rows("social-messages"), 0, "登出必须清空消息表");
  assert.equal(world.state.social, null, "登出必须清空消息 cache");
  return "消息页渲染并在离页/登出清空";
});

defineCase("person-social-only-read-body-and-xss-text", async () => {
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      return { status: 200, payload: { personId: JSON.parse(options.body).personId } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("omar") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "omar",
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") return { status: 200, payload: { personPartyId: "omar",
      timeMillis: "9000", available: true, sessions: socialSection([]),
      turns: socialSection([]), notes: [] } };
    if (url === "/api/me/evidence") return { status: 200, payload: { personPartyId: "omar",
      timeMillis: "9000", available: true, labels: socialSection([]),
      shares: socialSection([]), notes: [] } };
    if (url === "/api/me/social") return { status: 200, payload: personSocialView() };
    return { status: 200, payload: {} };
  }, "social-person");
  await bootSettled(world);
  await world.context.switchPerson("omar");

  assert.equal(world.rows("person-social-inbox"), 2, "收件箱必须渲染两封");
  const readRow = rowCells(world, "person-social-inbox", 0);
  const unreadRow = rowCells(world, "person-social-inbox", 1);
  assert.ok(readRow.includes("已读"), "已读行必须显示已读");
  assert.ok(readRow.includes("<b>x</b> 正文"), "已读正文按原文显示");
  const bodyCell = world.$("person-social-inbox").querySelector("tbody")
    .children[0].children[5];
  assert.equal(bodyCell.children.length, 0, "正文必须是纯文本，不得插入子元素");
  assert.ok(unreadRow.includes("未读"), "未读行必须显示未读");
  assert.ok(!unreadRow.includes("正文"), "未读不得显示正文");
  const outRow = rowCells(world, "person-social-outbox", 0);
  assert.ok(outRow.includes("未送达"), "未送达必须用统一口径");
  assert.ok(!outRow.includes("BLOCKED"), "人物不得看到屏蔽状态");

  world.context.showMode("login");
  assert.equal(world.rows("person-social-inbox"), 0, "切身份必须清空本人收件箱");
  assert.equal(world.rows("person-social-outbox"), 0, "切身份必须清空本人发件箱");
  assert.equal(world.state.personSocial, null, "切身份必须清空本人消息 cache");
  return "只读正文可见、未读不显示、XSS 文本化、切身份清空";
});

defineCase("person-social-late-response-discarded", async () => {
  let release = null;
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      return { status: 200, payload: { personId: JSON.parse(options.body).personId } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("omar") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "omar",
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") return { status: 200, payload: { personPartyId: "omar",
      timeMillis: "9000", available: true, sessions: socialSection([]),
      turns: socialSection([]), notes: [] } };
    if (url === "/api/me/evidence") return { status: 200, payload: { personPartyId: "omar",
      timeMillis: "9000", available: true, labels: socialSection([]),
      shares: socialSection([]), notes: [] } };
    if (url === "/api/me/social") {
      return new Promise(resolve => { release = resolve; });
    }
    return { status: 200, payload: {} };
  }, "social-late");
  await bootSettled(world);
  const running = world.context.switchPerson("omar");
  await waitFor(() => release !== null, "消息请求已发出");
  world.context.showMode("login");
  release({ status: 200, payload: personSocialView() });
  await running;
  assert.equal(world.rows("person-social-inbox"), 0, "迟到回应不得写入已切走的身份");
  assert.equal(world.state.personSocial, null, "迟到回应不得写回 cache");
  return "代次变化后迟到消息响应被丢弃";
});

// ---------------------------------------------------------------- 消息失败与回忆窗口（W5/W2）
defineCase("admin-social-failure-clears-old-rows", async () => {
  let fail = false;
  const world = makeWorld(async (method, url) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/admin/view") return { status: 200, payload: {
      runId: "run-1", timeMillis: 9000, position: 20, phase: "IDLE", paused: false,
      providerKind: "SCRIPTED", barrierPending: false, pendingCount: 0, deliveredCount: 0,
      accounts: [], owners: [], pending: [], trades: [], dividendReports: [],
      valuationsAvailable: true } };
    if (url === "/api/admin/keys") return { status: 200, payload: { slots: [] } };
    if (url === "/api/admin/routes") return { status: 200,
      payload: { revision: 1, baseUrl: "x", connected: false, routes: [] } };
    if (url === "/api/admin/capabilities") return { status: 200, payload: {
      supportedScenes: [], persons: [], mediaAvailable: false, position: "20",
      timeMillis: "9000" } };
    if (url === "/api/admin/social") {
      return fail ? { status: 503, payload: { error: "BUSY" } }
        : { status: 200, payload: adminSocialView() };
    }
    return { status: 200, payload: {} };
  }, "social-fail-admin");
  await bootSettled(world);
  world.context.showMode("admin");
  world.context.selectPage("social", true);
  await waitFor(() => world.rows("social-messages") === 2, "前置渲染");
  fail = true;
  await world.context.loadSocial(world.state.generation, world.state.pageVersion);
  assert.equal(world.rows("social-messages"), 0, "失败后不得保留旧消息行");
  assert.equal(world.rows("social-reports"), 0, "失败后不得保留旧报告行");
  assert.equal(world.state.social, null, "失败后不得保留旧 cache");
  assert.ok(world.$("social-note").textContent.includes("读取失败"),
    "必须显示加载失败提示，实际=" + world.$("social-note").textContent);
  return "管理员消息刷新失败清表并提示";
});

defineCase("person-social-failure-clears-and-explains", async () => {
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      return { status: 200, payload: { personId: JSON.parse(options.body).personId } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("omar") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "omar",
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") return { status: 200, payload: { personPartyId: "omar",
      timeMillis: "9000", available: true, sessions: socialSection([]),
      turns: socialSection([]), notes: [] } };
    if (url === "/api/me/evidence") return { status: 200, payload: { personPartyId: "omar",
      timeMillis: "9000", available: true, labels: socialSection([]),
      shares: socialSection([]), notes: [] } };
    if (url === "/api/me/social") return { status: 500, payload: { error: "WORLD_IO" } };
    return { status: 200, payload: {} };
  }, "social-fail-person");
  await bootSettled(world);
  await world.context.switchPerson("omar");
  assert.equal(world.rows("person-social-inbox"), 0, "失败不得留下旧收件箱");
  assert.equal(world.rows("person-social-outbox"), 0, "失败不得留下旧发件箱");
  assert.equal(world.state.personSocial, null, "失败不得写 cache");
  assert.equal(world.$("person-social").hidden, false, "失败要显示面板说明");
  assert.ok(world.$("person-social-note").textContent.includes("读取失败"),
    "必须有失败提示，实际=" + world.$("person-social-note").textContent);
  return "人物消息失败清表并提示";
});

defineCase("person-social-read-but-not-recallable", async () => {
  const view = personSocialView({ inbox: socialSection([{ messageId: "m1", from: "nina",
    channel: "私信", title: "约个时间", docId: "doc", version: "1", deliveredAt: "2",
    read: true, bodyAvailable: false, body: "" }]), outbox: socialSection([]),
    contacts: socialSection([]) });
  const world = makeWorld(async (method, url, options) => {
    if (url === "/api/session") return { status: 200, payload: { role: "NONE" } };
    if (url === "/api/session/person") {
      return { status: 200, payload: { personId: JSON.parse(options.body).personId } };
    }
    if (url === "/api/me/view") return { status: 200, payload: personView("omar") };
    if (url === "/api/me/interview") return { status: 200, payload: { personPartyId: "omar",
      invites: [], sessions: [], contacts: [] } };
    if (url === "/api/me/dialogue") return { status: 200, payload: { personPartyId: "omar",
      timeMillis: "9000", available: true, sessions: socialSection([]),
      turns: socialSection([]), notes: [] } };
    if (url === "/api/me/evidence") return { status: 200, payload: { personPartyId: "omar",
      timeMillis: "9000", available: true, labels: socialSection([]),
      shares: socialSection([]), notes: [] } };
    if (url === "/api/me/social") return { status: 200, payload: view };
    return { status: 200, payload: {} };
  }, "social-recall-window");
  await bootSettled(world);
  await world.context.switchPerson("omar");
  const row = rowCells(world, "person-social-inbox", 0);
  assert.ok(row.includes("已读"), "已读状态不得因回忆回落显示未读");
  assert.ok(row.includes("已读，当前无法回忆正文"),
    "必须区分已读但不可回忆，实际=" + row);
  assert.ok(!row.includes("未读"), "不得说成未读");
  return "已读但不可回忆有明确文案";
});

// ---------------------------------------------------------------- 运行
(async () => {
  const results = [];
  for (const item of cases) {
    if (only && !item.name.includes(only)) continue;
    try {
      const detail = await item.body();
      results.push({ name: item.name, ok: true, detail });
      console.log("PASS " + item.name + " | " + detail);
    } catch (failure) {
      results.push({ name: item.name, ok: false, detail: failure && failure.message });
      console.log("FAIL " + item.name + " | " + (failure && failure.message));
      console.log((failure && failure.stack ? failure.stack : String(failure))
        .split("\n").slice(0, 6).join("\n"));
    }
  }
  const failed = results.filter(item => !item.ok);
  console.log("---- 汇总 web=" + path.resolve(webDir));
  for (const item of results) console.log((item.ok ? "PASS " : "FAIL ") + item.name);
  console.log("WEB_INTERVIEW_BEHAVIOR=" + (failed.length ? "FAIL" : "PASS")
    + " passed=" + (results.length - failed.length) + "/" + results.length);
  if (failed.length) process.exitCode = 1;
})();
