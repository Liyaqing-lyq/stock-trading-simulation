// 18c-v2 前端契约检查：中文原因映射、未登记原因不假成功、BEGIN 包装标签、模板不适用文案。
// 用法：node engineering-reason-check.cjs <webDir>
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const webDir = process.argv[2]
  || path.join(__dirname, "../../main/resources/web");
const source = fs.readFileSync(path.join(webDir, "app.js"), "utf8");

function makeElement(tag = "div") {
  return {
    tag, attrs: {}, children: [], _text: "", hidden: false, value: "", dataset: {},
    get textContent() { return this._text; },
    set textContent(value) { this._text = value === null || value === undefined ? "" : String(value); },
    setAttribute(key, value) { this.attrs[key] = String(value); },
    getAttribute(key) { return this.attrs[key] === undefined ? null : this.attrs[key]; },
    removeAttribute(key) { delete this.attrs[key]; },
    appendChild(child) { this.children.push(child); return child; },
    append(...items) { this.children.push(...items); },
    replaceChildren(...items) { this.children = items; },
    addEventListener() { },
    querySelectorAll() { return []; },
    querySelector() { return makeElement("queried"); },
    classList: { add() { }, remove() { }, toggle() { } }
  };
}

function makeDocument() {
  const nodes = new Map();
  return {
    body: makeElement("body"),
    getElementById(id) {
      if (!nodes.has(id)) nodes.set(id, makeElement(id));
      return nodes.get(id);
    },
    createElement(tag) { return makeElement(tag); },
    createElementNS(namespace, tag) { return makeElement(tag); },
    createTextNode(value) { const node = makeElement("#text"); node.textContent = value; return node; },
    querySelectorAll() { return []; },
    addEventListener() { }
  };
}

const context = vm.createContext({
  document: makeDocument(), console, BigInt, Number, String, Object, Array, Map, Set, JSON,
  setTimeout, clearTimeout, setInterval, clearInterval
});
vm.runInContext(source, context);
vm.runInContext("globalThis.__api = { engineeringReason, engineeringReasonKnown,"
  + " engineeringKindLabel, engineeringReportText, engineeringReportVerdict,"
  + " engineeringStageLabel, ENGINEERING_UNKNOWN_REASON_TEXT, ENGINEERING_SCOPE_TEXT,"
  + " ENGINEERING_REASON_TEXT, ENGINEERING_TOOL_LABELS };", context);
const api = context.__api;

const cases = [];
function check(name, run) {
  try { run(); cases.push(["PASS", name]); }
  catch (failure) { cases.push(["FAIL", name + " :: " + failure.message]); }
}

check("known-reason-not-due", () => assert.equal(api.engineeringReason("NOT_DUE"), "尚未到期"));
check("known-reason-not-authorized", () =>
  assert.equal(api.engineeringReason("NOT_AUTHORIZED"), "当前岗位没有该权限"));
check("known-reason-budget", () =>
  assert.equal(api.engineeringReason("CUSTOMER_BUDGET_EXHAUSTED"), "客户期间预算不足"));
check("known-reason-not-read", () =>
  assert.equal(api.engineeringReason("OPPORTUNITY_NOT_READ"), "未实际读到此机会，不能签约"));
check("unknown-reason-neutral-text", () => {
  const text = api.engineeringReason("BRAND_NEW_CORE_CODE");
  assert.equal(text, "暂无结果说明，请查看原始记录");
  assert.ok(!text.includes("成功") && !text.includes("已受理") && !text.includes("未执行"),
    "未知原因既不标成功也不标失败，实际：" + text);
});
check("unknown-accepted-report-keeps-success-stage", () => {
  // 未知原因 + accepted=true：结果列仍是成功，说明列中性，不伪造“未执行”
  assert.equal(api.engineeringReportVerdict(true), "成功");
  assert.equal(api.engineeringReportText(true, "BRAND_NEW_CORE_CODE"), "暂无结果说明，请查看原始记录");
  assert.ok(!api.engineeringReportText(true, "BRAND_NEW_CORE_CODE").includes("未执行"));
});
check("unknown-rejected-report-keeps-reject-stage", () => {
  // 未知原因 + accepted=false：结果列仍是拒绝，说明列中性，不伪造“已受理/成功”
  assert.equal(api.engineeringReportVerdict(false), "拒绝");
  const text = api.engineeringReportText(false, "BRAND_NEW_CORE_CODE");
  assert.equal(text, "暂无结果说明，请查看原始记录");
  assert.ok(!text.includes("成功") && !text.includes("已受理"));
});
check("known-reason-reports-translate-exactly", () => {
  assert.equal(api.engineeringReportText(true, "SIGNED"), "合同已签署");
  assert.equal(api.engineeringReportText(false, "NOT_DUE"), "尚未到期");
  assert.equal(api.engineeringReportText(true, "STARTED"),
    "已真实开始（BEGIN 屏障通过；不代表签约或到账）");
  assert.equal(api.engineeringReportText(false, "REQUEST_PENDING"), "同键已有未回答请求，先等回应");
});
check("begin-and-finish-stage-labelled-separately", () => {
  assert.equal(api.engineeringStageLabel({ taskId: "t", kind: "ENGINEERING_BEGIN_V1" }),
    "BEGIN（真实开始）");
  assert.equal(api.engineeringStageLabel({ taskId: "t:finish", kind: "ENGINEERING_ACTION_V1" }),
    "FINISH（业务落账）");
  // 阶段与 accepted 无关：未知原因不会改变阶段判定
  assert.equal(api.engineeringStageLabel({ taskId: "t:finish", kind: "X", accepted: true }),
    "FINISH（业务落账）");
  assert.equal(api.engineeringStageLabel({ taskId: "t", kind: "X" }), "—");
});
check("unknown-reason-raw-preserved", () => {
  assert.equal(api.engineeringReasonKnown("BRAND_NEW_CORE_CODE"), false);
  assert.equal(api.engineeringReasonKnown("SIGNED"), true);
});
check("begin-kind-is-waiting-not-done", () => {
  const label = api.engineeringKindLabel("ENGINEERING_BEGIN_V1");
  assert.ok(label.includes("BEGIN"), "BEGIN 必须显示为包装，实际：" + label);
  assert.ok(label.includes("等待") || label.includes("回执"), "BEGIN 不得显示已完成，实际：" + label);
});
check("template-not-applicable-wording", () =>
  assert.equal(api.ENGINEERING_SCOPE_TEXT.NOT_APPLICABLE, "不适用于该经营模板"));
check("recover-button-wording", () =>
  assert.equal(api.ENGINEERING_TOOL_LABELS["engineering.recover"], "请求追索回款"));

let failed = 0;
for (const [verdict, name] of cases) {
  if (verdict === "FAIL") failed += 1;
  console.log(verdict + " " + name);
}
console.log("ENGINEERING_REASON_CONTRACT=" + (failed === 0 ? "PASS" : "FAIL")
  + " passed=" + (cases.length - failed) + "/" + cases.length);
process.exit(failed === 0 ? 0 : 1);
