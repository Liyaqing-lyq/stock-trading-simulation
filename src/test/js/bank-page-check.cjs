"use strict";

// 真实执行 resources/web/app.js 的银行渲染与本人银行读取（不联网）：用最小 DOM 桩加载真实文件。
// 断言：首屏财务概要、正常方向金额、贷款精简表与原始折叠、阶段语义（STARTED 不是完成）、
// 空态/旧档提示、只在 textContent 里放文本、本人请求不带 owner 参数、迟到响应不回填。
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const root = path.join(__dirname, "..", "..", "main", "resources", "web");

function element(id) {
  const node = {
    id: id, hidden: false, textContent: "", value: "", checked: false, children: [],
    listeners: {}, dataset: {}, className: "",
    appendChild(child) { node.children.push(child); return child; },
    append(...items) { node.children.push(...items); },
    addEventListener(type, handler) {
      (node.listeners[type] = node.listeners[type] || []).push(handler);
    },
    replaceChildren(...items) { node.children = items.slice(); },
    querySelector() {
      if (!node.body) node.body = element("tbody");
      return node.body;
    },
    querySelectorAll() { return []; },
    setAttribute() { },
    removeAttribute() { },
    focus() { }
  };
  return node;
}

const elements = new Map();
function get(id) {
  if (!elements.has(id)) elements.set(id, element(id));
  return elements.get(id);
}

let created = [];
const calls = [];
const responses = new Map();

const sandbox = {
  console: console,
  setTimeout: setTimeout,
  clearTimeout: clearTimeout,
  setInterval: () => 0,
  clearInterval: () => undefined,
  URLSearchParams: URLSearchParams,
  AbortController: AbortController,
  TextEncoder: TextEncoder,
  BigInt: BigInt,
  location: { pathname: "/", hash: "", search: "", origin: "http://127.0.0.1" },
  document: {
    getElementById: id => get(id),
    createElement: tagName => { created.push(String(tagName).toLowerCase()); return element("created"); },
    querySelector: () => get("query"),
    querySelectorAll: () => [],
    addEventListener: () => undefined,
    body: element("body"),
    hidden: false
  },
  fetch: async (url, options) => {
    const method = (options && options.method) || "GET";
    const body = options && options.body !== undefined ? JSON.parse(options.body) : undefined;
    calls.push([method, url, body]);
    const result = responses.get(url) || { status: 200, payload: {} };
    responses.delete(url);
    return { status: result.status, json: async () => result.payload };
  }
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
// 页面里 groups.js 先于 app.js 加载；本探针只验证银行，故给这两个跨文件全局最小桩。
sandbox.clearGroupsDom = () => undefined;
sandbox.placeGroupsPanel = () => undefined;

const context = vm.createContext(sandbox);
const code = fs.readFileSync(path.join(root, "app.js"), "utf8");
vm.runInContext(code, context, { filename: "app.js" });

function assert(flag, message) {
  if (!flag) throw new Error("ASSERT: " + message);
}

/** 等页面启动期的会话探测落地，避免它的迟到回包把身份改回登录。 */
const settle = () => new Promise(resolve => setTimeout(resolve, 30));

function rowsOf(tableId) {
  return get(tableId).body.children;
}

/** 真实 God 视图（字段与 Java bankAuditView 一致；数值取 Root 实测 S1 末态）。 */
function godView() {
  return {
    runId: "web-composed", available: true, reason: "",
    position: "121", timeMillis: "2592000000", phase: "COMPLETION",
    configFingerprint: "b835bd2d422dd4fe", digest: "32b08c2483f1a28a",
    backing: "0.00",
    archive: {
      archiveId: "COMPOSED-BANK-EXP", version: "24", label: "composed-v2",
      coverageRule: "DECLARED_UNIVERSE_V1",
      required: ["CET1R", "T1R", "TCR", "LEV", "SINGLE", "GROUP", "OFFBS", "LCR_EXP",
        "MATURITY_GAP", "IMPAIR_COVER"],
      implemented: ["CET1R"], omitted: ["NSFR"],
      provenanceStatus: "names_and_links_only_unverified", experimentOnly: true
    },
    banks: [{
      bankKey: "north", reserve: "3350.00", depositsTotal: "550.00",
      loansOnbookTotal: "90071992547409.92",
      customerDeposits: [{
        partyKey: "cufe", ownerId: "cufe", depositGl: "dep-cufe", amount: "450.00",
        nonAccrual: false, interestPayable: "0.00", interestPaid: "0.00"
      }],
      loans: [{
        loanId: "ln-req", borrowerKey: "cufe", onbook: "200.00", writtenOff: "0.00",
        interestReceivable: "0.33", interestPaid: "0.00", accruedInterest: "0.33",
        overdue: false, status: "DRAWN", statusLabel: "已放款", trancheId: "T1",
        rateText: "2%", tenorText: "30天",
        terms: {
          amount: "200.00", rateU: "20000", tenorMs: "2592000000",
          purpose: "<img src=x onerror=\"alert(1)\">", accepted: true,
          approvedBy: "erin", drawn: "200.00", status: "DRAWN"
        }
      }],
      interest: {
        interestReceivable: "0.33", interestPayable: "0.56",
        interestIncome: "0.00", interestExpense: "0.33"
      },
      equity: {
        paidIn: "3000.00", retained: "0.00", nonDistributable: "0.00",
        bookEquity: "2999.77", totalEquity: "2999.77", unbookedPnl: "-0.23"
      },
      totals: {
        assets: "3550.33", liabilities: "550.56", contra: "0.00", equity: "2999.77",
        pnl: "-0.23", balanced: true, complete: false,
        unsupported: ["世界路径未接：银行处置/清偿瀑布"],
        basis: "BankCompanyReport"
      },
      glRaw: { "INT.PAYABLE": "-0.56", "INT.INC": "0.00", "PAIDIN": "-3000.00" },
      capital: { equityBase: "3000.00", t1: "3000.00", tcr: "3000.00", pnl: "0.00" },
      gate: {
        status: "BLOCKED", actions: ["REQUIRED_BELOW_SCHEMA_FLOOR:NSFR"],
        metrics: [{ name: "CET1R", num: "3000.00", den: "0.00", value: null, state: "NA_ZERO_RWA" }],
        missing: ["CET1R:NA_ZERO_RWA"]
      }
    }],
    custody: {
      enabled: false, reason: "本档未启用托管人（托管成交尚未接入），客户受益权为缺项，不补零",
      pools: [], beneficial: []
    },
    actions: [
      { taskId: "T-1", commandId: "C-1", personPartyId: "erin", kind: "LOAN_APPROVE",
        kindLabel: "银行批准", bankKey: "north", customerKey: "cufe", accepted: true,
        reason: "LOAN_APPROVED", stage: "APPROVED", stageLabel: "银行已批准（未放款）",
        completed: false, receipt: "loan-approved", atMillis: "90", position: "3" },
      { taskId: "T-0", commandId: "C-0", personPartyId: "erin", kind: "LOAN_APPROVE",
        kindLabel: "银行批准", bankKey: "north", customerKey: "cufe", accepted: true,
        reason: "STARTED", stage: "STARTED", stageLabel: "已开始，待完成",
        completed: false, receipt: "", atMillis: "80", position: "2" },
      { taskId: "T-2", commandId: "C-2", personPartyId: "erin", kind: "LOAN_DRAW",
        kindLabel: "提款放款", bankKey: "north", customerKey: "cufe", accepted: true,
        reason: "SOME_NEW_CODE", stage: "UNMAPPED",
        stageLabel: "该帧成立（阶段未映射，按原码核对）",
        completed: false, receipt: "new-code", atMillis: "95", position: "4" }
    ],
    notes: ["全部金额为精确十进制字符串"]
  };
}

(async () => {
  await settle();
  created.length = 0;
  // 1) 首屏财务概要：真实数字 + 正常方向 + 不完整提示
  context.renderBank(godView());
  const headline = get("bank-headline").children.map(node => node.textContent);
  assert(headline.indexOf("3350.00") >= 0, "首屏必须显示真实准备金");
  assert(headline.indexOf("550.00") >= 0, "首屏必须显示真实存款负债");
  assert(headline.indexOf("3550.33") >= 0, "首屏必须显示权威资产合计");
  assert(headline.indexOf("-0.23") >= 0, "未结转损益必须保留负号");
  // 大额合计由服务端 BigDecimal 给出，浏览器原字符串显示（不经 Number 重算）
  const headlineText = headline.join(" | ");
  assert(headlineText.indexOf("90071992547409.92") >= 0,
    "首屏必须原样显示服务端大额合计");
  assert(headlineText.indexOf("90071992547410") < 0,
    "大额合计不得被 Number 舍入成 …410");
  assert(headlineText.indexOf("E13") < 0, "大额合计不得变成科学计数法");
  assert(typeof context.loansBookValue === "undefined"
    && typeof context.centsOf === "undefined"
    && typeof context.moneyText === "undefined",
    "三个浏览器端金额汇总 helper 必须已删除，不在前端重复记账");
  assert(get("bank-totals-note").textContent.indexOf("不完整") >= 0,
    "报表有未覆盖项时必须说明不完整，不能称完整");

  // 2) 摘要表：正常方向且损益保留负号
  const summary = rowsOf("bank-summary");
  assert(summary.length === 1, "银行摘要必须 1 行");
  const cells = summary[0].children.map(node => node.textContent);
  assert(cells.length === 9, "摘要必须 9 列，实际 " + cells.length);
  assert(cells[3] === "3550.33" && cells[4] === "550.56" && cells[5] === "2999.77",
    "资产/负债/权益必须用权威合计的正数方向");
  assert(cells[7] === "-0.23", "损益列必须是 -0.23，不能取绝对值");
  assert(cells[8] === "平衡", "A=L+E 必须显示平衡");
  assert(get("bank-summary-empty").hidden === true, "有银行时不得显示空态");

  // 3) 存款明细行数不变
  assert(rowsOf("bank-deposits").length === 1, "存款明细必须 1 行");
  assert(rowsOf("bank-deposits")[0].children.length === 8, "存款明细必须 8 列");

  // 4) 利息与权益：贷方科目显示为正，原始总账折叠保留负号
  const equity = rowsOf("bank-equity")[0].children.map(node => node.textContent);
  assert(equity.length === 11, "权益表必须 11 列，实际 " + equity.length);
  assert(equity[2] === "0.56", "应付利息必须显示 +0.56，而不是原始 -0.56");
  assert(equity[4] === "0.33", "利息支出必须显示 +0.33");
  assert(equity[5] === "3000.00", "实收资本必须显示 +3000.00");
  assert(equity[8] === "-0.23", "未结转累计损益必须为负");
  const glText = rowsOf("bank-glraw")
    .map(row => row.children.map(node => node.textContent).join(" ")).join("|");
  assert(glText.indexOf("-0.56") > 0 && glText.indexOf("-3000.00") > 0,
    "原始总账必须保留借正贷负原值");

  // 5) 贷款精简表 + 原始折叠
  const loans = rowsOf("bank-loans");
  assert(loans.length === 1, "贷款主表必须 1 行");
  const loanCells = loans[0].children.map(node => node.textContent);
  assert(loanCells.length === 6, "贷款主表必须 6 列，实际 " + loanCells.length);
  assert(loanCells[0] === "cufe" && loanCells[1] === "已放款", "借款人/状态必须中文可读");
  assert(loanCells[4] === "2%", "利率必须显示 2%");
  assert(loanCells[5] === "30天", "期限必须显示 30天");
  const raw = rowsOf("bank-loans-raw");
  assert(raw.length === 1 && raw[0].children.length === 16, "原始贷款表必须 16 列");
  const rawCells = raw[0].children.map(node => node.textContent);
  assert(rawCells[6] === "20000" && rawCells[7] === "2592000000",
    "原始利率/期限必须可审计");

  // 6) 门槛缺项仍可查（折叠区）
  assert(rowsOf("bank-metrics").length === 1, "指标行数必须与后端一致");
  assert(rowsOf("bank-metrics")[0].children[4].textContent === "缺项",
    "分母为零时必须显示缺项而不是 0");
  assert(get("bank-missing").textContent.indexOf("NA_ZERO_RWA") > 0,
    "缺项说明必须列出真实状态");

  // 7) 动作阶段：STARTED 不是完成，批准不是放款，未映射阶段不推断完成
  const actions = rowsOf("bank-actions");
  assert(actions.length === 3, "动作表必须三行");
  const approved = actions[0].children.map(node => node.textContent);
  assert(approved.length === 9, "动作表必须 9 列，实际 " + approved.length);
  assert(approved[3] === "银行已批准（未放款）", "批准阶段必须点明未放款");
  assert(approved[4] === "该帧成立", "完成帧才显示该帧成立");
  const started = actions[1].children.map(node => node.textContent);
  assert(started[3] === "已开始，待完成", "STARTED 必须显示已开始待完成");
  assert(started[4] === "已开始（未完成）", "STARTED 不能显示成立");
  const unmapped = actions[2].children.map(node => node.textContent);
  assert(unmapped[3] === "该帧成立（阶段未映射，按原码核对）", "未映射阶段必须保留原码说明");
  assert(unmapped[4] === "该帧成立（阶段未映射）", "未映射帧不得说完成或放款");
  assert(unmapped[5] === "SOME_NEW_CODE", "未映射帧必须保留原始原因码");

  // 8) 托管未启用：只给原因，不造池行
  assert(rowsOf("bank-custody-pools").length === 0, "未启用托管时不得有池行");
  assert(get("bank-custody-note").textContent.indexOf("未启用托管人") >= 0,
    "托管必须给出明确原因");

  // 9) 转义：贷款用途只进 textContent，不解析成元素
  assert(rawCells[8].indexOf("<img") === 0, "文本必须原样保留（不丢内容）");
  const forbidden = created.filter(tag =>
    tag !== "td" && tag !== "li" && tag !== "tr" && tag !== "dt" && tag !== "dd");
  assert(forbidden.length === 0, "渲染不得把文本解析成元素，实际新建 " + forbidden.join(","));

  // 10) 旧档：available=false 不显示零余额银行
  context.renderBank({
    runId: "web-scripted", available: false, reason: "本档未启用银行模块（旧档案）",
    position: "1", timeMillis: "0", phase: "OBSERVATION", banks: [], actions: [],
    custody: { enabled: false, reason: "本档未启用银行模块", pools: [], beneficial: [] },
    notes: ["旧档不升级"]
  });
  assert(rowsOf("bank-summary").length === 0, "旧档不得渲染银行行");
  assert(get("bank-unavailable").hidden === false, "旧档必须有可见说明");
  assert(get("bank-unavailable").textContent.indexOf("未启用银行") >= 0, "旧档说明必须解释原因");

  // 11) 本人页：只用 /api/me/bank，请求不带 owner 参数；阶段同样区分
  responses.set("/api/me/bank", {
    status: 200, payload: {
      runId: "web-composed", personPartyId: "erin", timeMillis: "2592000000",
      available: true, reason: "", tools: ["role.read"],
      owners: [{ ownerId: "north-owner", cash: "3350.00", deposits: "0.00", beneficial: "0.00" }],
      deposits: [{
        bankKey: "north", partyKey: "investor", amount: "0.00", offerId: "OFF-north",
        rateU: "20000", rateText: "2%", nonAccrual: false
      }],
      custody: [], actions: [{
        taskId: "T-1", kind: "LOAN_APPROVE", kindLabel: "银行批准", bankKey: "north",
        accepted: true, reason: "LOAN_APPROVED", stage: "APPROVED",
        stageLabel: "银行已批准（未放款）", atMillis: "90"
      }],
      actionsEnabled: true,
      actionsReason: "动作经真实 WorldRoleSession 排程",
      entitlements: ["DEPOSIT", "WITHDRAW", "PAY", "LOAN_REQUEST", "LOAN_ACCEPT",
        "LOAN_DRAW", "LOAN_REPAY", "LOAN_PAY_INTEREST"],
      options: {
        cash: [{ token: "CASH|BOR", label: "现金 BOR · 可用 1000.00 元", ownerId: "borrower" }],
        deposits: [{ token: "DEPOSIT|north|borrower", label: "存款 borrower · north · 0.00 元",
          partyKey: "borrower", ownerId: "borrower" }],
        payees: [{ token: "CASH|BOR", label: "现金 BOR" }],
        workMillis: { min: "1", max: "3600000", default: "1000" }
      },
      loans: [{ loanId: "ln-1", bankKey: "north", borrowerKey: "borrower",
        status: "PROPOSED", statusLabel: "已提案", amount: "200.00", rateText: "2%",
        rateU: "20000", tenorText: "30天", tenorMs: "2592000000", purpose: "周转",
        accepted: false, approvedBy: "", drawn: "0.00", trancheId: "", mine: true,
        bankSide: false, canAccept: true }]
    }
  });
  context.showMode("person");
  const generation = context.nextGeneration();
  await context.loadPersonalBank(generation);
  const bankCall = calls.filter(call => call[1] === "/api/me/bank").pop();
  assert(bankCall, "本人页必须请求 /api/me/bank");
  assert(bankCall[2] === undefined, "本人页请求不得带 body/owner");
  const owners = get("person-bank-owners").children.map(node => node.textContent);
  assert(owners.indexOf("north-owner") >= 0, "本人财产主体必须显示");
  const personActions = rowsOf("person-bank-actions");
  assert(personActions.length === 1 && personActions[0].children.length === 6,
    "本人动作表必须 6 列");
  const personCells = personActions[0].children.map(node => node.textContent);
  assert(personCells[2] === "银行已批准（未放款）", "本人页同样要区分批准与放款");
  assert(personCells[3] === "该帧成立", "本人页完成帧显示该帧成立");
  assert(rowsOf("person-bank-deposits").length === 1, "本人存款必须 1 行");
  assert(get("person-bank-note").textContent.indexOf("排程") > 0,
    "动作说明必须交代真实排程语义");

  // 11b) 本人页不得展示全局位置；利率用服务端格式化
  const statusText = get("person-bank-status").textContent;
  assert(statusText.indexOf("世界时点") >= 0, "本人页只展示世界时点");
  assert(statusText.indexOf("位置") < 0, "本人页不得出现位置/序号字样");
  assert(statusText.indexOf("undefined") < 0, "缺字段不得显示 undefined");
  const depositCells = rowsOf("person-bank-deposits")[0].children.map(node => node.textContent);
  assert(depositCells[4] === "2%", "存款利率必须显示服务端格式 2%，实际 " + depositCells[4]);
  assert(depositCells.indexOf("20000") < 0, "原始定标值不得作为展示值");

  // 12) 本人银行操作：只列授权动作、文本金额、提交不含身份字段
  assert(get("person-bank-ops").hidden === false, "有可用动作时必须显示操作面板");
  const kindValues = get("person-bank-kind").children.map(node => node.value);
  assert(kindValues.includes("DEPOSIT") && kindValues.includes("LOAN_ACCEPT"),
    "必须列出授权动作");
  assert(!kindValues.includes("LOAN_ACCRUE"), "系统计息不得出现在人物动作里");
  assert(rowsOf("person-bank-loans").length === 1, "必须列出本人可见合同");
  assert(rowsOf("person-bank-loans")[0].children[4].textContent === "30天",
    "合同期限必须可读");

  get("person-bank-kind").value = "DEPOSIT";
  context.togglePersonalBankRows();
  assert(get("person-bank-source-row").hidden === false, "存入必须选账户");
  assert(get("person-bank-payee-row").hidden === true, "存入不需要收款人");
  get("person-bank-source").value = "CASH|BOR";
  get("person-bank-amount").value = "100.00";
  get("person-bank-work").value = "1000";
  responses.set("/api/me/bank/action", { status: 200, payload: {
    receipt: "SCHEDULED:web-bank-1-t", kind: "DEPOSIT", dueTimeMillis: "1001",
    status: "SCHEDULED" } });
  await context.submitPersonalBankAction();
  const depositCall = calls.filter(call => call[1] === "/api/me/bank/action").pop();
  assert(depositCall && depositCall[2].kind === "DEPOSIT", "必须提交存入动作");
  assert(depositCall[2].amount === "100.00", "金额必须按文本原样提交");
  assert(depositCall[2].sourceToken === "CASH|BOR", "资金桶来自服务端选项");
  assert(!("ownerId" in depositCall[2]) && !("personId" in depositCall[2])
    && !("customerKey" in depositCall[2]), "请求不得自报身份/主体");
  assert(get("person-bank-result").textContent.indexOf("已排程") >= 0,
    "成功必须显示已排程而不是已完成");

  // 贷款申请：条款来自输入，但利率按整数定标、期限按天换算
  get("person-bank-kind").value = "LOAN_REQUEST";
  context.togglePersonalBankRows();
  assert(get("person-bank-terms-row").hidden === false, "申请必须能填条款");
  get("person-bank-amount").value = "200.00";
  get("person-bank-rate").value = "2.5";
  get("person-bank-tenor").value = "30";
  get("person-bank-purpose").value = "周转";
  responses.set("/api/me/bank/action", { status: 200, payload: {
    receipt: "SCHEDULED:web-bank-2-t", kind: "LOAN_REQUEST", dueTimeMillis: "2001",
    status: "SCHEDULED" } });
  await context.submitPersonalBankAction();
  const loanCall = calls.filter(call => call[1] === "/api/me/bank/action").pop();
  assert(loanCall[2].rateU === 25000, "2.5% 必须换算为定标 25000，实际 " + loanCall[2].rateU);
  assert(loanCall[2].tenorMs === 2592000000, "30 天必须换算为毫秒");
  assert(loanCall[2].loanId === undefined, "新申请不得指向别的合同");

  // 生命周期：必须带合同标识且不能改条款
  get("person-bank-kind").value = "LOAN_ACCEPT";
  context.togglePersonalBankRows();
  assert(get("person-bank-loan-row").hidden === false, "接受条款必须选合同");
  assert(get("person-bank-terms-row").hidden === true, "生命周期不得改条款");
  get("person-bank-loan").value = "ln-1";
  responses.set("/api/me/bank/action", { status: 400, payload: {
    error: "REJECTED", message: "当前身份不能执行该动作：LOAN_ACCEPT" } });
  await context.submitPersonalBankAction();
  assert(get("person-bank-error").textContent.indexOf("当前身份不能执行") >= 0,
    "拒绝原因必须可见");
  assert(get("person-bank-submit").disabled === false, "失败后按钮必须恢复可用");

  // 经理选择公司主体后，出款桶和发送的主体必须一致；不能默认为经理个人。
  context.renderPersonalBankOps({available:true, personPartyId:"carol",
    entitlements:["DEPOSIT","WITHDRAW"], loans:[], options:{
      subjects:[{token:"eng-a",label:"任职公司 eng-a"},{token:"eng-b",label:"任职公司 eng-b"}],
      cash:[{token:"CASH|ENG-A",label:"A现金",partyKey:"eng-a"}],
      deposits:[{token:"DEPOSIT|north|eng-a",label:"A存款",partyKey:"eng-a"},
        {token:"DEPOSIT|north|eng-b",label:"B存款",partyKey:"eng-b"}],payees:[]}});
  get("person-bank-kind").value="WITHDRAW";
  context.togglePersonalBankRows();
  assert(get("person-bank-source").value==="DEPOSIT|north|eng-a","取款应选本公司存款");
  get("person-bank-subject").value="eng-b";
  context.togglePersonalBankRows();
  assert(get("person-bank-source").children.length===1,"不可混入另一公司出款桶");
  assert(get("person-bank-source").value==="DEPOSIT|north|eng-b","切换公司同步更换出款桶");
  get("person-bank-amount").value="10.00";
  responses.set("/api/me/bank/action",{status:200,payload:{receipt:"SCHEDULED:company",dueTimeMillis:"1001"}});
  await context.submitPersonalBankAction();
  const companyCall=calls.filter(call=>call[1]==="/api/me/bank/action").pop();
  assert(companyCall[2].organizationPartyId==="eng-b","真实请求必须携带所选公司主体");
  assert(companyCall[2].sourceToken==="DEPOSIT|north|eng-b","主体与源账户必须同属所选公司");

  // 13) 身份切换清空（含操作面板）
  context.clearPersonalBank();
  assert(get("person-bank-ops").hidden === true, "切换身份必须隐藏操作面板");
  assert(get("person-bank-kind").children.length === 0, "切换身份必须清空动作选项");
  assert(rowsOf("person-bank-loans").length === 0, "切换身份必须清空合同表");
  assert(get("person-bank-amount").value === "", "切换身份必须清空金额输入");

  // 14) 旧身份的迟到本人银行响应不得回填到新身份
  context.clearPersonalBank();
  assert(get("person-bank-owners").children.length === 0, "切换身份必须清空本人银行卡片");
  assert(rowsOf("person-bank-deposits").length === 0, "切换身份必须清空本人存款表");
  responses.set("/api/me/bank", {
    status: 200, payload: {
      available: true, personPartyId: "bob", position: "9", timeMillis: "1",
      owners: [{ ownerId: "fund-a", cash: "1000.00", deposits: "0.00", beneficial: "0.00" }],
      deposits: [], custody: [], actions: [], actionsEnabled: false, actionsReason: "x"
    }
  });
  const staleGeneration = context.nextGeneration();
  await context.loadPersonalBank(staleGeneration - 1);
  assert(get("person-bank-owners").children.length === 0,
    "旧身份的迟到响应不得回填前人资金");
  assert(get("person-bank-status").textContent === "", "旧响应不得改写状态文字");

  console.log("BANK-NODE-OK");
})().catch(failure => {
  console.error(failure);
  process.exit(1);
});
