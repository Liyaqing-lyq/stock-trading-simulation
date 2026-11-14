// 18c Web 可用性切片：人物工程表单的字段裁剪、默认值、payload 与校验检查。
// 用法：node engineering-form-check.cjs <webDir>
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const webDir = process.argv[2] || path.join(__dirname, "../../main/resources/web");
const source = fs.readFileSync(path.join(webDir, "app.js"), "utf8");
const css = fs.readFileSync(path.join(webDir, "style.css"), "utf8");
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
  // 真实 DOM 里给节点设置 id 会以该 id 可被 getElementById 找到；stub 必须一致。
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
  return {
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
}

const document = makeDocument();
const fetchCalls = [];
const context = vm.createContext({
  document, console, BigInt, Number, String, Object, Array, Map, Set, JSON,
  setTimeout, clearTimeout, setInterval, clearInterval,
  // fetch 只记录调用并保持悬置：点击处理器的首个同步段即可断言 POST 字段，不触发后续渲染。
  fetch(url, options) {
    fetchCalls.push({ url, options });
    return new Promise(() => { });
  }
});
vm.runInContext(source, context);
vm.runInContext("globalThis.__api = { state, renderEngineeringFields, engineeringFieldValues,"
  + " engineeringValidate, engineeringActionBody, clearPersonalEngineering,"
  + " fillEngineeringSelectors, refreshEngineeringTools, engineeringToolsOf,"
  + " renderPeriodicBlock, currentPeriodicCard, fillPersonalEngineeringCompanies,"
  + " scheduleDocumentReading, currentPersonCompany,"
  + " ENGINEERING_TOOL_FIELDS, ENGINEERING_FIELD_SPECS } ;", context);
const api = context.__api;

/** 触发元素上已登记的事件处理器（真实点击路径）。 */
function fire(node, type) {
  const handlers = (node && node.listeners && node.listeners[type]) || [];
  assert.ok(handlers.length > 0, "元素没有登记 " + type + " 处理器");
  for (const handler of handlers) handler({ preventDefault() { } });
}

/** 在元素树里按文本找按钮。 */
function buttonByText(root, needle) {
  if (!root) return null;
  if (root.tag === "button" && String(root.textContent || "").includes(needle)) return root;
  for (const child of root.children || []) {
    const found = buttonByText(child, needle);
    if (found) return found;
  }
  return null;
}

function periodicCard() {
  const draft = {
    draftId: "periodic-draft:eng-a:1:1", docId: "periodic-material:eng-a:1",
    version: "1", preparedBy: "dave", preparedByName: "戴夫",
    preparedAt: "2592001000", approvalCount: "0", requiredApprovals: "3"
  };
  return {
    available: true, god: false, companyPartyId: "eng-a", seat: true, manager: true,
    mySeatId: "ea4", seats: [
      { seatId: "ea1", personPartyId: "alice", personName: "爱丽丝", chair: true },
      { seatId: "ea4", personPartyId: "dave", personName: "戴夫", chair: false }
    ],
    periods: [{
      period: "1", kind: "MONTHLY", capturedAt: "2592000000", deadline: "3888000000",
      materialDocId: "periodic-material:eng-a:1", materialVersion: "1",
      myReadMaterial: false, myRecallEligible: false,
      evidence: [
        { ref: "account:ENG-A", dimensions: ["CASH_SOLVENCY"], asOf: "2592000000" },
        { ref: "contract:B-1", dimensions: ["OPERATING_COMMITMENT", "RISK"], asOf: "2592000000" }
      ],
      unavailableDimensions: [
        { dimension: "DISCLOSURE", reason: "NO_ELIGIBLE_EVIDENCE" },
        { dimension: "LONG_TERM_INVESTMENT", reason: "DIMENSION_NOT_AVAILABLE" }
      ],
      drafts: [draft], latestDraft: draft,
      score: { closed: false, seatCount: null, total: null, ballots: [] },
      authorizations: [],
      releases: [],
      publication: { publicDocId: null, released: false, versions: "0",
        latestVersion: null, latestPublishedAt: null, late: false, missed: false },
      corrections: "0",
      pendingActions: [{ taskId: "t1", action: "PREPARE", personPartyId: "dave",
        dueTimeMillis: "2592002000", state: "SCHEDULED" }]
    }]
  };
}

function person(worldTime, companies) {
  api.state.mode = "person";
  api.state.personEngineering = {
    personPartyId: "dave", position: "93", timeMillis: String(worldTime),
    companies: companies || [managerCompany()]
  };
  api.fillEngineeringSelectors(api.state.personEngineering.companies);
  const select = document.getElementById("eng-company");
  if (select.children.length) select.value = select.children[0].value;
}
function managerCompany(extra) {
  return Object.assign({
    companyId: "eng-a", companyName: "工程公司A", template: "ENGINEERING",
    manager: true, seatId: "ea4", chair: false, periodic: periodicCard(),
    tools: ["engineering.sign", "engineering.startWork", "engineering.invoice",
      "engineering.requestAcceptance", "engineering.requestPayment",
      "engineering.requestRetention", "engineering.impair", "engineering.writeOff",
      "engineering.recover", "engineering.paySupplier", "engineering.terminate",
      "engineering.waive", "board.convene", "board.attend", "board.addMaterial",
      "board.vote", "board.proposeBudget", "board.proposeAppointment",
      "board.proposeWaiver", "board.implement", "periodic.attend", "periodic.prepare",
      "periodic.score", "periodic.approve", "periodic.authorize", "periodic.publish"]
  }, extra || {});
}

function openTool(tool) {
  api.renderEngineeringFields(tool);
  return (api.state.engineeringFieldIds || []).join(",");
}
function setField(key, value) {
  const spec = api.ENGINEERING_FIELD_SPECS[key];
  document.getElementById(spec.id).value = value;
}
function collect(tool) {
  api.renderEngineeringFields(tool);
  const values = api.engineeringFieldValues();
  return { values, body: api.engineeringActionBody(tool, values) };
}

const cases = [];
function check(name, run) {
  try { run(); cases.push(["PASS", name]); }
  catch (failure) { cases.push(["FAIL", name + " :: " + failure.message]); }
}

// ---------------------------------------------------------------- 1 字段裁剪
check("sign-only-contract", () => assert.equal(openTool("engineering.sign"), "contractId"));
check("start-work-three-fields", () =>
  assert.equal(openTool("engineering.startWork"), "contractId,workId,units"));
check("invoice-only-contract", () =>
  assert.equal(openTool("engineering.invoice"), "contractId"));
check("vote-meeting-and-choice", () =>
  assert.equal(openTool("board.vote"), "meetingId,vote"));
check("convene-four-fields", () =>
  assert.equal(openTool("board.convene"), "meetingId,materialDeadline,opensAt,closesAt"));
check("waive-five-fields", () =>
  assert.equal(openTool("engineering.waive"),
    "contractId,amount,purpose,authorizationRef,durationMillis"));
check("pay-supplier-amount-purpose", () =>
  assert.equal(openTool("engineering.paySupplier"), "amount,purpose"));
check("implement-only-meeting", () =>
  assert.equal(openTool("board.implement"), "meetingId"));
check("unknown-tool-no-fields", () => assert.equal(openTool("engineering.unknown"), ""));

// 每个工具都必须在字段目录里（防漏配）
check("all-tools-have-specs", () => {
  for (const tool of managerCompany().tools) {
    assert.ok(api.ENGINEERING_TOOL_FIELDS[tool], "缺少字段配置：" + tool);
  }
});

// ---------------------------------------------------------------- 2 必填/可选标记
check("required-marker-on-sign", () => {
  openTool("engineering.sign");
  const labels = document.getElementById("eng-fields").children.map(child => child.children[0].textContent);
  assert.ok(labels.some(item => item.includes("合同标识") && item.includes("必填")), labels.join("|"));
});
check("optional-marker-on-terminate-purpose", () => {
  openTool("engineering.terminate");
  const labels = document.getElementById("eng-fields").children.map(child => child.children[0].textContent);
  assert.ok(labels.some(item => item.includes("用途") && item.includes("可选")), labels.join("|"));
});

// ---------------------------------------------------------------- 3 默认值与“不代造”
check("due-default-follows-world-time", () => {
  person(700);
  api.renderEngineeringFields("engineering.sign");
  assert.equal(document.getElementById("eng-due").value, "1700");
});
check("convene-times-ordered", () => {
  person(1000);
  api.renderEngineeringFields("board.convene");
  const values = api.engineeringFieldValues();
  assert.equal(values.materialDeadline, "61000");
  assert.equal(values.opensAt, "121000");
  assert.equal(values.closesAt, "181000");
  assert.equal(values.meetingId, "", "会议号不得自动生成");
});
check("no-fabricated-ids", () => {
  person(1000);
  for (const tool of ["board.addMaterial", "board.proposeAppointment", "board.proposeWaiver",
    "engineering.waive"]) {
    api.renderEngineeringFields(tool);
    const values = api.engineeringFieldValues();
    for (const key of ["meetingId", "materialId", "docId", "appointmentId",
      "appointPersonId", "waiverId", "authorizationRef"]) {
      if (!(key in values)) continue;
      assert.equal(values[key], "", tool + " 的 " + key + " 不得预填");
    }
  }
});
check("budget-period-zero-default", () => {
  person(1000);
  api.renderEngineeringFields("board.proposeBudget");
  assert.equal(api.engineeringFieldValues().period, "0");
});
check("material-version-default", () => {
  person(1000);
  api.renderEngineeringFields("board.addMaterial");
  assert.equal(api.engineeringFieldValues().docVersion, "1");
});
check("appointment-window-default", () => {
  person(1000);
  api.renderEngineeringFields("board.proposeAppointment");
  const values = api.engineeringFieldValues();
  assert.equal(values.appointRole, "COMPANY_MANAGER");
  assert.ok(Number(values.endsAt) > Number(values.effectiveAt));
});

// ---------------------------------------------------------------- 4 payload
check("payload-only-visible-fields", () => {
  person(700);
  api.renderEngineeringFields("engineering.startWork");
  setField("contractId", "B-1");
  setField("workId", "W-B1");
  setField("units", "2");
  const values = api.engineeringFieldValues();
  const body = api.engineeringActionBody("engineering.startWork", values);
  assert.deepEqual(Object.keys(body).sort(), ["commandId", "companyId", "contractId",
    "dueTimeMillis", "expectedPosition", "taskId", "tool", "units", "workId"]);
  assert.equal(body.units, "2");
});
check("explicit-zero-preserved", () => {
  person(700);
  api.renderEngineeringFields("engineering.requestPayment");
  setField("contractId", "B-1");
  setField("trancheIndex", "0");
  const values = api.engineeringFieldValues();
  const body = api.engineeringActionBody("engineering.requestPayment", values);
  assert.equal(body.trancheIndex, "0", "显式 0 必须保留");
  assert.equal(body.units, undefined, "没有该字段时不得发送");
});
check("no-stale-value-on-tool-switch", () => {
  person(700);
  api.renderEngineeringFields("engineering.sign");
  setField("contractId", "B-1");
  api.renderEngineeringFields("board.vote");
  setField("meetingId", "m1");
  setField("vote", "YES");
  const values = api.engineeringFieldValues();
  assert.equal(values.contractId, undefined, "旧合同值不得留在新工具里");
  const body = api.engineeringActionBody("board.vote", values);
  assert.equal(body.contractId, undefined);
  assert.equal(body.vote, "YES");
});
check("empty-optional-omitted", () => {
  person(700);
  api.renderEngineeringFields("engineering.terminate");
  setField("contractId", "B-1");
  setField("purpose", "");
  const values = api.engineeringFieldValues();
  const body = api.engineeringActionBody("engineering.terminate", values);
  assert.equal("purpose" in body, false);
});

// ---------------------------------------------------------------- 5 校验
function errorsFor(tool, filled) {
  api.renderEngineeringFields(tool);
  for (const [key, value] of Object.entries(filled || {})) setField(key, value);
  return api.engineeringValidate(tool, api.engineeringFieldValues());
}
check("missing-required-reported-in-chinese", () => {
  person(700);
  const errors = errorsFor("engineering.sign", { contractId: "" });
  assert.ok(errors.some(item => item.includes("合同标识") && item.includes("必须填写")), errors.join("|"));
});
check("units-zero-rejected", () => {
  person(700);
  const errors = errorsFor("engineering.startWork",
    { contractId: "B-1", workId: "W1", units: "0" });
  assert.ok(errors.some(item => item.includes("大于 0")), errors.join("|"));
});
check("amount-zero-rejected", () => {
  person(700);
  const errors = errorsFor("engineering.impair", { contractId: "B-1", amount: "0" });
  assert.ok(errors.some(item => item.includes("大于 0")), errors.join("|"));
});
check("bad-time-order-rejected", () => {
  person(1000);
  const errors = errorsFor("board.convene",
    { meetingId: "m1", materialDeadline: "9000", opensAt: "8000", closesAt: "9500" });
  assert.ok(errors.some(item => item.includes("开会时点")), errors.join("|"));
});
check("budget-without-limit-rejected", () => {
  person(1000);
  const errors = errorsFor("board.proposeBudget", { meetingId: "m1", period: "0" });
  assert.ok(errors.some(item => item.includes("至少")), errors.join("|"));
});
check("appointment-without-ends-rejected", () => {
  person(1000);
  const errors = errorsFor("board.proposeAppointment", { meetingId: "m1",
    appointmentId: "a1", appointPersonId: "dave", appointAction: "APPOINT",
    effectiveAt: "2000", endsAt: "" });
  assert.ok(errors.some(item => item.includes("任职期限")), errors.join("|"));
});
check("valid-payload-passes-validation", () => {
  person(700);
  const errors = errorsFor("engineering.sign", { contractId: "B-1" });
  assert.equal(errors.length, 0, errors.join("|"));
});

// ---------------------------------------------------------------- 6 工具清单与公司隔离
check("chair-gets-conveneWaiver", () => {
  const chair = managerCompany({ chair: true });
  assert.ok(api.engineeringToolsOf(chair).includes("board.conveneWaiver"));
  assert.ok(!api.engineeringToolsOf(managerCompany({ chair: false }))
    .includes("board.conveneWaiver"), "非主席不得出现该选项");
});
check("no-other-company-options", () => {
  api.state.mode = "person";
  api.state.personEngineering = {
    personPartyId: "dave", position: "93", timeMillis: "700",
    companies: [managerCompany(), managerCompany({ companyId: "eng-b", companyName: "工程公司B" })]
  };
  api.fillEngineeringSelectors(api.state.personEngineering.companies);
  const select = document.getElementById("eng-company");
  const values = select.children.map(option => option.value);
  assert.deepEqual(values, ["eng-a", "eng-b"]);
  // 只从服务端返回的可见公司里取选项：未返回的公司不会被补出来
  api.state.personEngineering.companies = [managerCompany()];
  api.fillEngineeringSelectors(api.state.personEngineering.companies);
  assert.deepEqual(document.getElementById("eng-company").children.map(o => o.value), ["eng-a"]);
});

// ---------------------------------------------------------------- 7 身份切换清理
check("identity-switch-clears-form", () => {
  person(700);
  api.renderEngineeringFields("engineering.sign");
  setField("contractId", "B-1");
  api.clearPersonalEngineering();
  assert.equal((api.state.engineeringFieldIds || []).length, 0);
  assert.equal(document.getElementById("eng-fields").children.length, 0);
  assert.equal(document.getElementById("eng-due").value, "");
  assert.equal(document.getElementById("eng-company").children.length, 0);
  assert.equal(document.getElementById("eng-tool").children.length, 0);
});

// ---------------------------------------------------------------- 8 静态结构与窄屏
check("html-has-no-static-tool-fields", () => {
  for (const id of ["eng-contract", "eng-work", "eng-amount", "eng-meeting", "eng-vote"]) {
    assert.ok(!html.includes('id="' + id + '"'), "静态 HTML 不应再固定放 " + id);
  }
  assert.ok(html.includes('id="eng-fields"'));
  assert.ok(html.includes("novalidate"), "表单需关闭原生校验，用中文提示替代");
});
check("narrow-screen-rules", () => {
  assert.ok(/@media\(max-width:760px\)\{[\s\S]*?\.eng-grid\{grid-template-columns:1fr\}/.test(css)
    || /@media\(max-width:760px\)\{\s*\n\s*\.eng-grid\{grid-template-columns:1fr\}/.test(css),
    "缺少 760px 单列规则");
  assert.ok(css.includes(".eng-field"), "缺少字段容器样式");
  assert.ok(/@media\(max-width:480px\)/.test(css), "缺少 480px 补充规则");
});
check("chinese-terms-in-main-copy", () => {
  assert.ok(!html.includes("AR 当期") && !html.includes("AR 质保"), "表头仍有英文缩写");
  assert.ok(!html.includes("逐合同 claims"), "章节标题仍暴露 claims");
  assert.ok(html.includes("数据来源（技术细节，可展开）"));
});

// ---------------------------------------------------------------- 9 周期表单与渲染
function selectCompany(id) {
  document.getElementById("eng-company").value = id;
  api.refreshEngineeringTools();
}
function allText(node) {
  let out = node.textContent || "";
  for (const child of node.children || []) out += "\n" + allText(child);
  return out;
}

check("periodic-tool-field-cropping", () => {
  person(1000);
  assert.equal(openTool("periodic.attend"), "periodicPeriod,recused");
  assert.equal(openTool("periodic.prepare"), "periodicPeriod,note");
  assert.equal(openTool("periodic.score"), "periodicPeriod");
  assert.equal(openTool("periodic.approve"), "periodicPeriod,draftId,draftVersion");
  assert.equal(openTool("periodic.authorize"),
    "periodicPeriod,draftId,draftVersion,publisherPersonId,expiresAt");
  assert.equal(openTool("periodic.publish"),
    "periodicPeriod,draftId,draftVersion,authorizationId");
});
check("periodic-score-only-eligible-evidence", () => {
  person(1000);
  openTool("periodic.score");
  const cash = document.getElementById("eng-evidence-CASH_SOLVENCY");
  const longTerm = document.getElementById("eng-evidence-LONG_TERM_INVESTMENT");
  assert.ok(cash.children.some(option => option.value === "account:ENG-A"));
  assert.ok(!cash.children.some(option => option.value === "contract:B-1"),
    "跨维度证据不得出现在现金偿债选择里");
  assert.equal(longTerm.disabled, true, "无合格长期投入证据必须禁用");
  assert.equal(document.getElementById("eng-score-LONG_TERM_INVESTMENT").disabled, true);
  assert.equal(document.getElementById("eng-score-CASH_SOLVENCY").value, "",
    "不得预填 0 分");
});
check("periodic-explicit-zero-score-preserved", () => {
  person(1000);
  openTool("periodic.score");
  document.getElementById("eng-score-CASH_SOLVENCY").value = "0";
  document.getElementById("eng-evidence-CASH_SOLVENCY").value = "account:ENG-A";
  const values = api.engineeringFieldValues();
  assert.equal(values.metrics.length, 1);
  assert.equal(values.metrics[0].score, "0");
  assert.equal(values.metrics[0].sourceRef, "account:ENG-A");
  const errors = api.engineeringValidate("periodic.score", values);
  assert.equal(errors.length, 0, errors.join("|"));
  const body = api.engineeringActionBody("periodic.score", values);
  assert.equal(body.period, "1");
  assert.equal(body.metrics[0].score, "0");
  assert.equal("asOf" in body.metrics[0], false, "asOf 由服务端取，不由页面填写");
});
check("periodic-missing-metric-rejected-not-zero", () => {
  person(1000);
  openTool("periodic.score");
  document.getElementById("eng-score-CASH_SOLVENCY").value = "90";
  const values = api.engineeringFieldValues();
  const errors = api.engineeringValidate("periodic.score", values);
  assert.ok(errors.some(item => item.includes("必须选择本期合格证据")), errors.join("|"));
  assert.equal((values.metrics || []).length, 1);
  assert.equal(values.metrics[0].score, "90", "缺证据时不得改写成 0");
});
check("periodic-hidden-fields-not-submitted-on-switch", () => {
  person(1000);
  openTool("periodic.authorize");
  document.getElementById("eng-publisher").value = "alice";
  document.getElementById("eng-expires").value = "999999";
  openTool("periodic.publish");
  const values = api.engineeringFieldValues();
  assert.equal(values.publisherPersonId, undefined, "切工具后旧发布人不得留在请求里");
  assert.equal(values.expiresAt, undefined, "切工具后旧授权期限不得留在请求里");
  const body = api.engineeringActionBody("periodic.publish", values);
  assert.equal("publisherPersonId" in body, false);
  assert.equal("expiresAt" in body, false);
});
check("periodic-company-switch-clears-values", () => {
  person(1000);
  api.renderEngineeringFields("periodic.prepare");
  document.getElementById("eng-note").value = "旧公司的更正说明";
  api.state.personEngineering.companies = [managerCompany({ companyId: "eng-b" })];
  api.fillEngineeringSelectors(api.state.personEngineering.companies);
  api.renderEngineeringFields("periodic.prepare");
  const values = api.engineeringFieldValues();
  assert.equal(values.note, "", "切公司重建字段后旧备注不得残留");
});
check("periodic-render-only-scheduled-not-published", () => {
  const container = document.createElement("div");
  api.renderPeriodicBlock(container, periodicCard(), { personView: true });
  const rendered = allText(container);
  assert.ok(rendered.includes("尚未发布"), rendered);
  assert.ok(rendered.includes("已排程（待人执行）"), rendered);
  assert.ok(!rendered.includes("公开正文"), "未发布时不得出现公开正文");
});
check("periodic-render-missing-total-not-zero", () => {
  const card = periodicCard();
  const row = card.periods[0];
  row.score = {
    closed: true, status: "INCOMPLETE", reason: "NO_QUORUM", seatCount: "1", total: null,
    ballots: [{ seatId: "ea4", personPartyId: "dave", personName: "戴夫",
      submittedAt: "2592003000", status: "INCOMPLETE",
      missingDimensions: ["DISCLOSURE", "LONG_TERM_INVESTMENT"],
      metrics: [{ dimension: "CASH_SOLVENCY", score: "90" }], rationale: "" }]
  };
  row.drafts[0].approvalCount = "1";
  const container = document.createElement("div");
  api.renderPeriodicBlock(container, card, { personView: true });
  const rendered = allText(container);
  assert.ok(rendered.includes("未形成总分"), rendered);
  assert.ok(rendered.includes("无总分（缺维不补）"), rendered);
  assert.ok(rendered.includes("长期投入（无合格证据）"), rendered);
  assert.ok(!rendered.includes("总分 0"), rendered);
});
check("convene-meeting-hint-is-for-new-meeting", () => {
  person(1000);
  openTool("board.convene");
  const text = allText(document.getElementById("eng-fields"));
  assert.ok(text.includes("本次要新召集的会议标识"), text);
  assert.ok(!text.includes("必须是自己召集或参与的会议"), text);
});
check("periodic-styles-present", () => {
  assert.ok(css.includes(".periodic-period"), "缺少周期区块样式");
  assert.ok(css.includes(".periodic-body pre"), "缺少正文样式");
  assert.ok(css.includes(".eng-score-row"), "缺少评分行样式");
});

// ---------------------------------------------------------------- 10 授权回填与遗忘语义
function grant(dataset) {
  return Object.assign({
    authorizationId: "periodic-authorization:eng-a:1:1",
    draftId: "periodic-draft:eng-a:1:2",
    draftVersion: "1",
    publisherPersonId: "dave", publisherName: "戴夫",
    expiresAt: "3999000000", status: "PENDING", mine: true
  }, dataset || {});
}

check("periodic-publish-prefills-own-grant", () => {
  api.state.mode = "person";
  const card = periodicCard();
  const row = card.periods[0];
  // 指定发布人不是准备者：本人响应里没有草稿行，只给后端允许的授权绑定。
  row.drafts = [];
  row.latestDraft = null;
  row.authorizations = [grant()];
  api.state.personEngineering = {
    personPartyId: "dave", position: "93", timeMillis: "1000",
    companies: [managerCompany({ periodic: card })]
  };
  api.fillEngineeringSelectors(api.state.personEngineering.companies);
  document.getElementById("eng-company").value = "eng-a";
  api.renderEngineeringFields("periodic.publish");
  const values = api.engineeringFieldValues();
  assert.equal(values.draftId, "periodic-draft:eng-a:1:2");
  assert.equal(values.draftVersion, "1");
  assert.equal(values.authorizationId, "periodic-authorization:eng-a:1:1");
  const errors = api.engineeringValidate("periodic.publish", values);
  assert.equal(errors.length, 0, "本人授权绑定的草稿必须能通过校验：" + errors.join("|"));
});

check("periodic-publish-ignores-other-grant", () => {
  api.state.mode = "person";
  const card = periodicCard();
  const row = card.periods[0];
  row.drafts = [];
  row.latestDraft = null;
  row.authorizations = [grant({ mine: false, publisherPersonId: "alice", publisherName: "爱丽丝" })];
  api.state.personEngineering = {
    personPartyId: "dave", position: "93", timeMillis: "1000",
    companies: [managerCompany({ periodic: card })]
  };
  api.fillEngineeringSelectors(api.state.personEngineering.companies);
  document.getElementById("eng-company").value = "eng-a";
  api.renderEngineeringFields("periodic.publish");
  const values = api.engineeringFieldValues();
  assert.equal(values.authorizationId, "", "他人授权不得回填到本人表单");
  assert.equal(values.draftId, "", "他人授权绑定的草稿不得回填");
  const select = document.getElementById("eng-auth");
  assert.ok(!select.children.some(option => option.value === "periodic-authorization:eng-a:1:1"),
    "他人授权不得出现在可选项里");
});

function releaseRow(extra) {
  return Object.assign({
    publicDocId: "company-statement:eng-a:1", publicVersion: "1", version: "1",
    versionResolved: true, draftVersion: "1", publishedAt: "2593000000",
    publisherPersonId: "dave", publisherName: "戴夫", late: false,
    title: "周期财报 eng-a 第1期 v1", read: true, recallable: true, forgotten: false,
    bodyAvailable: true, body: "期间基准\n公司=eng-a", requiresReading: false
  }, extra || {});
}

check("periodic-forgotten-body-needs-reread", () => {
  const card = periodicCard();
  const row = card.periods[0];
  row.releases = [releaseRow({
    read: true, recallable: false, forgotten: true, bodyAvailable: false, body: null,
    requiresReading: true
  })];
  row.publication = { publicDocId: "company-statement:eng-a:1", released: true, versions: "1",
    latestVersion: "1", latestPublishedAt: "2593000000", late: false, missed: false };
  const container = document.createElement("div");
  api.renderPeriodicBlock(container, card, { personView: true, showReadState: true });
  const rendered = allText(container);
  assert.ok(rendered.includes("当前不可回忆"), rendered);
  assert.ok(rendered.includes("重新阅读公开财报 v1"), rendered);
  assert.ok(!rendered.includes("期间基准"), "遗忘后不得回显正文");
});

check("periodic-unpublished-draft-keeps-public-version", () => {
  const card = periodicCard();
  const row = card.periods[0];
  row.releases = [releaseRow()];
  const second = Object.assign({}, row.drafts[0], {
    draftId: "periodic-draft:eng-a:1:2", docId: "periodic-material:eng-a:1:2", version: "1"
  });
  row.drafts = [row.drafts[0], second];
  row.latestDraft = second;
  row.publication = { publicDocId: "company-statement:eng-a:1", released: true, versions: "1",
    latestVersion: "1", latestPublishedAt: "2593000000", late: false, missed: false };
  const container = document.createElement("div");
  api.renderPeriodicBlock(container, card, { personView: true, showReadState: true });
  const rendered = allText(container);
  assert.ok(rendered.includes("未发表的草稿修订不增加公开版本"), rendered);
  assert.ok(rendered.includes("v1"), rendered);
  assert.ok(!rendered.includes("v2"), "只有一条公开发布记录时不得显示公开 v2：" + rendered);
});

check("periodic-unresolved-public-version-no-body", () => {
  const card = periodicCard();
  const row = card.periods[0];
  row.releases = [releaseRow({
    publicVersion: null, version: null, versionResolved: false,
    read: false, recallable: false, forgotten: false, bodyAvailable: false, body: null,
    requiresReading: true
  })];
  row.publication = { publicDocId: "company-statement:eng-a:1", released: true, versions: "1",
    latestVersion: null, latestPublishedAt: "2593000000", late: false, missed: false };
  const container = document.createElement("div");
  api.renderPeriodicBlock(container, card, { personView: true, showReadState: true });
  const rendered = allText(container);
  assert.ok(rendered.includes("未定位"), rendered);
  assert.ok(!rendered.includes("阅读公开财报"), "版本未定位时不给阅读入口");
  assert.ok(!rendered.includes("期间基准"), rendered);
});

// ---------------------------------------------------------------- 11 章程公司入口与可读时间
check("manufacturing-convene-appointment-form", () => {
  person(1000);
  assert.equal(openTool("board.conveneAppointment"),
    "meetingId,materialDeadline,opensAt,closesAt");
  const values = api.engineeringFieldValues();
  assert.equal(values.materialDeadline, "61000");
  assert.equal(values.opensAt, "121000");
  assert.equal(values.closesAt, "181000");
  assert.equal(values.meetingId, "", "会议号不得自动生成");
});

check("periodic-world-time-readable", () => {
  const container = document.createElement("div");
  api.renderPeriodicBlock(container, periodicCard(), { personView: true });
  const rendered = allText(container);
  assert.ok(rendered.includes("运行30天0小时"), rendered);
  assert.ok(rendered.includes("2592000000 毫秒"), "精确毫秒必须同时保留");
});

check("periodic-awaiting-score-vs-missing-evidence", () => {
  const card = periodicCard();
  const row = card.periods[0];
  row.dimensionScope = "ALL_SEATS";
  row.dimensionStates = [
    { dimension: "CASH_SOLVENCY", hasEvidence: true, scored: false, state: "AWAITING_SCORE" },
    { dimension: "LONG_TERM_INVESTMENT", hasEvidence: false, scored: false,
      state: "DIMENSION_NOT_AVAILABLE" }
  ];
  const container = document.createElement("div");
  api.renderPeriodicBlock(container, card, { personView: true });
  const rendered = allText(container);
  assert.ok(rendered.includes("有合格证据、还没有人评分"), rendered);
  assert.ok(rendered.includes("长期投入（无合格机制）"), rendered);
  assert.ok(!rendered.includes("现金偿债（无合格证据）"), "有证据不得报成缺项：" + rendered);
});

// ---------------------------------------------------------------- 12 治理说明阅读入口（真实点击）
function governanceCompany(extra) {
  const card = managerCompany();
  card.template = "MANUFACTURING";
  card.companyId = "cufe";
  card.companyName = "制造公司";
  card.governanceDocId = "company-governance:cufe:1";
  card.governanceDocVersion = "1";
  card.governanceDocTitle = "制造公司开局治理说明（模拟冻结配置）";
  card.governanceDocRead = false;
  card.governanceDocRecallable = false;
  card.governanceDocBody = null;
  card.managerVacant = true;
  return Object.assign(card, extra || {});
}

function renderGovernanceCard(card) {
  person(1000, [card]);
  api.fillPersonalEngineeringCompanies([card]);
  return document.getElementById("person-engineering-companies");
}

check("governance-note-card-renders-unread", () => {
  const container = renderGovernanceCard(governanceCompany());
  const rendered = allText(container);
  assert.ok(rendered.includes("本公司治理说明"), rendered);
  assert.ok(rendered.includes("制造公司开局治理说明（模拟冻结配置）"), rendered);
  assert.ok(rendered.includes("company-governance:cufe:1 v1"), rendered);
  assert.ok(rendered.includes("未读"), rendered);
  assert.ok(rendered.includes("开始阅读治理说明 v1"), rendered);
  assert.ok(!rendered.includes("模拟冻结配置（开局夹具"), "未读不得显示正文：" + rendered);
});

check("governance-note-read-button-posts-own-card-fields", () => {
  const card = governanceCompany();
  const container = renderGovernanceCard(card);
  const button = buttonByText(container, "开始阅读治理说明");
  assert.ok(button, "缺少开始阅读按钮");
  fetchCalls.length = 0;
  fire(button, "click");
  assert.equal(fetchCalls.length, 1, "点击必须真实发起一次请求");
  const call = fetchCalls[0];
  assert.equal(call.url, "/api/me/read");
  assert.equal(call.options.method, "POST");
  const body = JSON.parse(call.options.body);
  assert.equal(body.docId, card.governanceDocId, "docId 必须来自本人卡片");
  assert.equal(body.version, card.governanceDocVersion);
  assert.equal(body.expectedPosition, "93", "位置来自本人视图");
  assert.ok(String(body.commandId).startsWith("web-read-"), body.commandId);
  assert.equal("personPartyId" in body, false, "body 不能自带人物身份");
});

check("governance-note-read-shows-body-only-when-recalled", () => {
  const container = renderGovernanceCard(governanceCompany({
    governanceDocRead: true, governanceDocRecallable: true,
    governanceDocBody: "模拟冻结配置（开局夹具，不是真实公司披露）\n公司=cufe"
  }));
  const rendered = allText(container);
  assert.ok(rendered.includes("已读 · 当前可回忆"), rendered);
  assert.ok(rendered.includes("模拟冻结配置（开局夹具"), "读后必须显示真实正文：" + rendered);
  assert.equal(buttonByText(container, "阅读治理说明"), null, "可回忆时不应再给阅读按钮");
});

check("governance-note-forgotten-needs-reread", () => {
  const container = renderGovernanceCard(governanceCompany({
    governanceDocRead: true, governanceDocRecallable: false, governanceDocBody: null
  }));
  const rendered = allText(container);
  assert.ok(rendered.includes("当前不可回忆（需重读）"), rendered);
  assert.ok(rendered.includes("重新阅读治理说明 v1"), rendered);
  assert.ok(!rendered.includes("模拟冻结配置（开局夹具"), "遗忘后不得回显正文：" + rendered);
  fetchCalls.length = 0;
  fire(buttonByText(container, "重新阅读治理说明"), "click");
  assert.equal(fetchCalls.length, 1);
  assert.equal(JSON.parse(fetchCalls[0].options.body).docId, "company-governance:cufe:1");
});

check("identity-switch-clears-governance-note", () => {
  renderGovernanceCard(governanceCompany());
  api.clearPersonalEngineering();
  const container = document.getElementById("person-engineering-companies");
  assert.equal(container.children.length, 0, "切人物后旧卡片必须被清空");
  api.fillPersonalEngineeringCompanies([managerCompany()]);
  assert.ok(!allText(container).includes("本公司治理说明"), "切到没有该说明的公司不得残留旧文");
});

check("add-material-defaults-to-own-governance-doc", () => {
  person(1000, [governanceCompany()]);
  api.renderEngineeringFields("board.addMaterial");
  const withNote = api.engineeringFieldValues();
  assert.equal(withNote.docId, "company-governance:cufe:1", "默认应填入本人卡片的真实 docId");
  assert.equal(withNote.docVersion, "1");
  person(1000, [managerCompany()]);
  api.renderEngineeringFields("board.addMaterial");
  assert.equal(api.engineeringFieldValues().docId, "", "没有该说明时不得预填任何 docId");
});

check("convene-appointment-meeting-hint-is-new-meeting", () => {
  person(1000);
  openTool("board.conveneAppointment");
  const text = allText(document.getElementById("eng-fields"));
  assert.ok(text.includes("本次要新召集的会议标识"), text);
  assert.ok(!text.includes("必须是自己召集或参与的会议"), text);
});

let failed = 0;
for (const [verdict, name] of cases) {
  if (verdict === "FAIL") failed += 1;
  console.log(verdict + " " + name);
}
console.log("ENGINEERING_FORM_CONTRACT=" + (failed === 0 ? "PASS" : "FAIL")
  + " passed=" + (cases.length - failed) + "/" + cases.length);
process.exit(failed === 0 ? 0 : 1);
