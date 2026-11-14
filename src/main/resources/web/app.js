"use strict";

// 本机工作台前端：只用 fetch，不把任何凭据写入浏览器存储。
// 关键约束：
// 1) 每次切换身份都递增 generation，迟到的 fetch 回应一律丢弃，绝不渲染到新身份下；
// 2) 离开管理员视角时清空管理员表格、记录序号、完整密钥与隐藏计时器；
// 3) 所有异步入口都捕获异常并给出可见中文提示，不留未处理 Promise。

const state = {
  mode: "none",
  generation: 0,
  position: null,
  revealTimer: null,
  routeRevision: null,
  page: "overview",
  pageVersion: 0,
  details: {},
  live: null,
  liveTimer: null,
  liveRequest: 0,
  liveBusy: false,
  recallRequest: 0,
  beliefRequest: 0,
  marketRequest: 0,
  market: null,
  performanceRequest: 0,
  performanceHistory: null,
  mediaRequest: 0,
  capabilities: null,
  dialogue: null,
  personDialogue: null,
  evidence: null,
  social: null,
  groups: null,
  personGroups: null,
  personSocial: null,
  personEvidence: null,
  engineering: null,
  engineeringCompany: "",
  engineeringRequest: 0,
  history: null,
  branch: null,
  personEngineering: null
};

const ADMIN_TABLES = ["accounts", "owners", "pending", "trades", "dividends", "keys"];
const DETAIL_PAGES = {
  company: "/api/admin/company",
  media: "/api/admin/media",
  interview: "/api/admin/interview",
  performance: "/api/admin/performance",
  org: "/api/admin/people",
  information: "/api/admin/information",
  bank: "/api/admin/bank"
};
const DETAIL_TABLES = ["company-report", "company-inventory", "company-orders",
  "company-batches", "company-contracts", "company-receivables", "company-bills",
  "company-seats", "company-meetings", "company-budgets", "company-commitments",
  "company-reviews", "company-evidence", "company-reports", "performance-accounts",
  "performance-owners", "performance-holdings", "performance-flows",
  "performance-points", "performance-history-flows", "org-parties",
  "org-owners", "org-appointments", "org-mandates", "org-reports",
  "information-documents", "information-active", "information-closed",
  "information-observations", "information-readers", "information-reports",
  "market-bars", "market-gaps",
  "engineering-report", "engineering-seats", "engineering-appointments",
  "engineering-contracts", "engineering-works", "engineering-budgets",
  "engineering-commitments", "engineering-meetings", "engineering-requests",
  "engineering-responses", "engineering-payments", "engineering-waivers",
  "engineering-tasks", "engineering-reports",
  "bank-summary", "bank-deposits", "bank-loans", "bank-loans-raw", "bank-equity",
  "bank-glraw", "bank-gates", "bank-metrics", "bank-custody-pools",
  "bank-custody-beneficial", "bank-actions"];
const PERSON_TABLES = ["person-accounts", "person-holdings", "person-market",
  "person-bank-deposits", "person-bank-actions"];

const $ = (id) => document.getElementById(id);

function text(value) {
  return value === null || value === undefined || value === "" ? "—" : String(value);
}

function cell(row, value, className) {
  const td = document.createElement("td");
  td.textContent = text(value);
  if (className) {
    td.className = className;
  }
  row.appendChild(td);
  return td;
}

function fillTable(tableId, rows, builder) {
  const body = $(tableId).querySelector("tbody");
  body.replaceChildren();
  for (const item of rows || []) {
    const tr = document.createElement("tr");
    builder(tr, item);
    body.appendChild(tr);
  }
}

function show(id, visible) {
  $(id).hidden = !visible;
}

function setMessage(id, message) {
  const node = $(id);
  node.textContent = message || "";
  node.hidden = !message;
}

function setStatus(message) {
  $("status").textContent = message || "";
}

function nextGeneration() {
  state.generation += 1;
  return state.generation;
}

function isCurrent(generation) {
  return generation === state.generation;
}

async function request(method, url, body) {
  const headers = {};
  if (method !== "GET") {
    headers["Content-Type"] = "application/json";
    headers["X-World-Request"] = "1";
  }
  let response;
  try {
    response = await fetch(url, {
      method,
      headers,
      credentials: "same-origin",
      body: body === undefined ? undefined : JSON.stringify(body)
    });
  } catch (failure) {
    throw new Error("无法连接本机服务");
  }
  let payload = null;
  try {
    payload = await response.json();
  } catch (ignored) {
    payload = null;
  }
  return { status: response.status, payload };
}

function clearSecrets() {
  if ($("reveal-confirm").open) $("reveal-confirm").close("cancel");
  if (state.revealTimer) {
    clearTimeout(state.revealTimer);
    state.revealTimer = null;
  }
  const node = $("reveal");
  node.textContent = "";
  node.hidden = true;
}

function clearAdminDom() {
  state.capabilities = null;
  $("live-persons").replaceChildren();
  clearLiveDom();
  state.position = null;
  state.routeRevision = null;
  $("routes-list").replaceChildren();
  $("routes-result").textContent = "";
  $("route-provider").textContent = "";
  clearMarketDom();
  for (const name of ["accounts", "pending", "trades", "position"]) $("metric-" + name).textContent = "—";
  for (const id of ADMIN_TABLES) {
    fillTable(id, [], () => undefined);
  }
  clearDetailDom();
  $("snapshot").replaceChildren();
  setMessage("admin-error", "");
  setMessage("advance-result", "");
  setMessage("login-error", "");
  $("advance-target").value = "";
  $("person-id").value = "";
  clearSecrets();
}

function clearPersonDom() {
  clearPersonalMedia();
  clearPersonalInterview();
  clearPersonalEvidence();
  clearPersonalSocial();
  clearPersonalEngineering();
  $("person-name").textContent = "—";
  $("person-summary").replaceChildren();
  for (const id of PERSON_TABLES) {
    fillTable(id, [], () => undefined);
  }
  $("person-catalog").textContent = "";
  setMessage("person-error", "");
}

function showMode(mode) {
  clearGroupsDom();
  state.mode = mode;
  document.body.dataset.mode = mode;
  $("navigation").hidden = mode !== "admin";
  $("refresh").hidden = mode === "login";
  $("context-time").textContent = mode === "login" ? "本机实验环境" : "读取确认状态…";
  nextGeneration();
  if (mode !== "admin") {
    clearAdminDom();
    clearHistoryDom();
  }
  if (mode !== "person") {
    clearPersonDom();
  }
  show("login", mode === "login");
  show("admin", mode === "admin");
  show("person", mode === "person");
  $("logout").hidden = mode === "login";
  placeGroupsPanel();
}

function sessionLost() {
  clearGroupsDom();
  showMode("login");
  setStatus("会话已失效，请重新登录");
  setMessage("login-error", "会话已失效，请使用新的引导凭据重新登录。");
}

function providerLabel(kind) {
  if (kind === "SCRIPTED") {
    return "脚本运行（尚未接入模型）";
  }
  if (kind === "UNAVAILABLE") {
    return "未启用";
  }
  return String(kind);
}

// ---------------------------------------------------------------- 管理员

async function enterAdmin() {
  showMode("admin");
  selectPage("overview", false);
  setStatus("管理员视角");
  await refreshAdmin();
}

async function refreshAdmin() {
  stopLivePoll();
  const generation = state.generation;
  const pageVersion = ++state.pageVersion;
  const current = () => isCurrent(generation) && state.mode === "admin" && pageVersion === state.pageVersion;
  let result;
  try {
    result = await request("GET", "/api/admin/view");
  } catch (failure) {
    if (current()) {
      setMessage("admin-error", failure.message);
    }
    return;
  }
  if (!current()) {
    return;
  }
  if (result.status === 401 || result.status === 403) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    setMessage("admin-error", "读取世界快照失败（" + result.status + "），请稍后重试。");
    return;
  }
  setMessage("admin-error", "");
  renderAdmin(result.payload);
  await refreshKeys(generation, current);
  if (!current()) return;
  await refreshRoutes(generation, current);
  if (!current()) return;
  if (state.page === "models") await refreshLive();
  if (DETAIL_PAGES[state.page]) {
    state.details[state.page] = null;
    await loadDetail(state.page);
  }
  if (state.page === "market" && current()) {
    await loadMarketHistory(generation, pageVersion);
  }
  if (state.page === "evidence" && current()) {
    await loadEvidence(generation, pageVersion);
  }
  if (state.page === "social" && current()) {
    await loadSocial(generation, pageVersion);
  }
  if (state.page === "groups" && current()) {
    await loadGroups(generation, pageVersion);
  }
  if (state.page === "engineering" && current()) {
    await loadEngineering(generation, pageVersion);
  }
  if (state.page === "history" && current()) {
    await loadHistoryIndex();
    await loadBranchRuns();
  }
}

function renderAdminCut(view) {
  $("context-time").textContent = (view.runId ? view.runId + " · " : "") + view.timeMillis + " 毫秒";
  setStatus("管理员视角 · 已确认记录 " + view.position);
}

function renderAdmin(view) {
  state.position = view.position;
  $("context-time").textContent = view.runId + " · " + view.timeMillis + " 毫秒";
  $("metric-accounts").textContent = String(view.accounts.length);
  $("metric-pending").textContent = String(view.pendingCount);
  $("metric-trades").textContent = String(view.trades.length);
  $("metric-position").textContent = view.position;
  if (!$("advance-target").value) $("advance-target").value = String(BigInt(view.timeMillis) + 1000n);
  setStatus("管理员视角 · 已确认记录 " + view.position);

  const snapshot = $("snapshot");
  snapshot.replaceChildren();
  const pairs = [
    ["运行标识", view.runId],
    ["已确认记录", view.position],
    ["模拟时点（毫秒）", view.timeMillis],
    ["最后阶段", view.phase],
    ["状态", view.paused ? "已暂停" : "正常"],
    ["决定提供方", providerLabel(view.providerKind)],
    ["未决屏障", view.barrierPending ? "存在" : "无"],
    ["待办 / 已投递通知", view.pendingCount + " / " + view.deliveredCount]
  ];
  for (const [label, value] of pairs) {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = text(value);
    snapshot.append(dt, dd);
  }

  fillTable("accounts", view.accounts, (tr, row) => {
    cell(tr, row.accountId);
    cell(tr, row.ownerId);
    cell(tr, row.cash, "num");
  });

  fillTable("owners", view.owners, (tr, row) => {
    cell(tr, row.ownerId);
    cell(tr, ({ COMPLETE: "完整", UNSUPPORTED_ORGANIZATION: "组织经营估值未接入",
      MISSING_PRICE: "缺少价格", INCOMPLETE: "估值不完整" })[row.status] || row.status);
    cell(tr, row.netWorth, "num");
    cell(tr, row.cash, "num");
    cell(tr, row.holdings, "num");
  });
  $("owners-empty").hidden = view.valuationsAvailable !== false;

  fillTable("pending", view.pending, (tr, row) => {
    cell(tr, row.taskId);
    cell(tr, row.dueTimeMillis, "num");
    cell(tr, row.phase);
    cell(tr, row.kind);
  });
  $("pending-empty").hidden = (view.pending || []).length !== 0;

  fillTable("trades", view.trades, (tr, row) => {
    cell(tr, row.tradeId);
    cell(tr, row.symbol);
    cell(tr, row.price, "num");
    cell(tr, row.quantity, "num");
    cell(tr, row.timeMillis, "num");
    cell(tr, row.buyAccountId);
    cell(tr, row.sellAccountId);
  });
  $("trades-empty").hidden = (view.trades || []).length !== 0;

  fillTable("dividends", view.dividendReports, (tr, row) => {
    cell(tr, row.taskId);
    cell(tr, row.kind);
    cell(tr, row.schemeId);
    cell(tr, row.position, "num");
    cell(tr, row.accepted ? "已接受：" + row.reason : "已拒绝：" + row.reason);
  });
  $("dividends-empty").hidden = (view.dividendReports || []).length !== 0;
}

async function refreshKeys(generation, current = () => true) {
  let result;
  try {
    result = await request("GET", "/api/admin/keys");
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "admin" && current()) {
      setMessage("admin-error", failure.message);
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin" || !current()) {
    return;
  }
  if (result.status === 401 || result.status === 403) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    setMessage("admin-error", "读取密钥槽位失败（" + result.status + "）。");
    return;
  }
  fillTable("keys", result.payload.slots, (tr, row) => {
    cell(tr, row.slotId);
    cell(tr, row.masked);
    cell(tr, row.configured ? "已配置" : "未配置");
    const actions = document.createElement("td");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "短暂查看";
    button.addEventListener("click", () => {
      reveal(row.slotId).catch((failure) => setMessage("admin-error", failure.message));
    });
    actions.appendChild(button);
    tr.appendChild(actions);
  });
}

async function reveal(slotId) {
  if (state.mode !== "admin") {
    return;
  }
  const generation = state.generation;
  const dialog = $("reveal-confirm");
  $("reveal-question").textContent = "确认在本机屏幕短暂显示 " + slotId + " 的完整凭据？";
  dialog.returnValue = "";
  dialog.showModal();
  const confirmed = await new Promise(resolve => dialog.addEventListener("close",
    () => resolve(dialog.returnValue === "confirm"), { once: true }));
  if (!confirmed || !isCurrent(generation) || state.mode !== "admin") {
    return;
  }
  const result = await request("POST", "/api/admin/keys/" + encodeURIComponent(slotId) + "/reveal",
    { confirm: true });
  if (!isCurrent(generation) || state.mode !== "admin") {
    return;
  }
  if (result.status !== 200 || !result.payload) {
    setMessage("admin-error", "读取完整凭据被拒绝（" + result.status + "）。");
    return;
  }
  const node = $("reveal");
  node.textContent = slotId + "：" + result.payload.secret;
  node.hidden = false;
  clearTimeout(state.revealTimer);
  state.revealTimer = setTimeout(() => {
    node.textContent = "";
    node.hidden = true;
    state.revealTimer = null;
  }, 15000);
}

async function advance() {
  if (state.mode !== "admin" || state.position === null) {
    return;
  }
  const generation = state.generation;
  const target = $("advance-target").value.trim();
  if (!/^(0|[1-9][0-9]*)$/.test(target)) {
    setMessage("advance-result", "目标模拟时点必须是非负整数毫秒。");
    return;
  }
  const result = await request("POST", "/api/admin/advance",
    { expectedPosition: state.position, targetMillis: target });
  if (!isCurrent(generation) || state.mode !== "admin") {
    return;
  }
  if (result.status === 200) {
    setMessage("advance-result", "已推进到记录 " + result.payload.position + "。");
    await refreshAdmin();
    return;
  }
  if (result.status === 409) {
    setMessage("advance-result", "记录已变化（当前 " + result.payload.position + "），已刷新。");
    await refreshAdmin();
    return;
  }
  setMessage("advance-result", "推进被拒绝（" + result.status + "）："
    + ((result.payload && result.payload.message) || "请稍后重试"));
}

// ---------------------------------------------------------------- 个人

async function enterPerson(personId) {
  showMode("person");
  $("person-name").textContent = personId;
  setStatus("个人视角");
  await refreshPerson();
}

async function refreshPerson() {
  clearPersonalMedia();
  clearPersonalInterview();
  clearPersonalBank();
  const generation = state.generation;
  let result;
  try {
    result = await request("GET", "/api/me/view");
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "person") {
      setMessage("person-error", failure.message);
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== "person") {
    return;
  }
  if (result.status === 401) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    setMessage("person-error", "读取个人视图失败（" + result.status + "）。");
    return;
  }
  setMessage("person-error", "");
  const view = result.payload;
  renderPersonalMedia(view.media);
  await loadGroups(generation, null);
  if (!isCurrent(generation) || state.mode !== "person") return;
  await renderPersonalInterview((view.media && view.media.tools) || view.tools, generation);
  if (!isCurrent(generation) || state.mode !== "person") return;
  $("context-time").textContent = "人物观察 · " + view.timeMillis + " 毫秒";
  $("person-name").textContent = view.personPartyId;
  await loadPersonalBank(generation);
  if (!isCurrent(generation) || state.mode !== "person") return;

  const summary = $("person-summary");
  summary.replaceChildren();
  const pairs = [
    ["模拟时点（毫秒）", view.timeMillis],
    ["任职", view.appointments],
    ["可见资料", view.catalog],
    ["阅读记录", view.readings],
    ["观察", view.observations],
    ["可用工具", (view.tools || []).join("、")]
  ];
  for (const [label, value] of pairs) {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = text(value);
    summary.append(dt, dd);
  }

  fillTable("person-accounts", view.accounts, (tr, row) => {
    cell(tr, row.accountId);
    cell(tr, row.kind);
    cell(tr, row.cash, "num");
    cell(tr, row.availableCash, "num");
  });
  fillTable("person-holdings", view.holdings, (tr, row) => {
    cell(tr, row.accountId);
    cell(tr, row.symbol);
    cell(tr, row.quantity, "num");
    cell(tr, row.available, "num");
  });
  fillTable("person-market", view.market, (tr, row) => {
    cell(tr, row.symbol);
    cell(tr, row.lastPrice, "num");
    cell(tr, row.referencePrice, "num");
    cell(tr, ({ OPENING_REFERENCE: "开局参考", EX_DIVIDEND_REFERENCE: "除息参考", LAST_TRADE: "实际成交" })[row.referenceSource] || row.referenceSource || "暂无");
    cell(tr, row.lastTradeQuantity, "num");
    cell(tr, row.bestBid, "num");
    cell(tr, row.bestAsk, "num");
  });
  $("person-catalog").textContent = view.catalog === 0
    ? "当前没有可见资料。" : "共 " + view.catalog + " 条可见目录项。";
  await renderPersonalDialogue(generation);
  await renderPersonalEvidence(generation);
  await renderPersonalSocial(generation);
  await renderPersonalEngineering(generation);
}

async function switchPerson(personId) {
  const result = await request("POST", "/api/session/person", { personId });
  if (result.status === 401) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    setMessage("login-error", "该人物不可见，或当前会话已失效。");
    show("login", true);
    return;
  }
  await enterPerson(result.payload.personId);
}

// ---------------------------------------------------------------- 会话

async function login(event) {
  event.preventDefault();
  setMessage("login-error", "");
  const token = $("bootstrap").value.trim();
  if (!token) {
    setMessage("login-error", "请输入本机引导凭据。");
    return;
  }
  const result = await request("POST", "/api/session", { bootstrap: token });
  $("bootstrap").value = "";
  if (result.status !== 200) {
    setMessage("login-error", "登录失败：凭据错误或已被使用。");
    return;
  }
  await enterAdmin();
}

async function logout() {
  try {
    await request("POST", "/api/session/logout", {});
  } catch (ignored) {
    // 本机服务不可达时也要清空本地显示
  }
  showMode("login");
  setStatus("已退出登录");
  setMessage("login-error", "已退出，请使用新的引导凭据重新登录。");
}

async function backToAdmin() {
  await logout();
  setStatus("已退出个人视角");
}

async function boot() {
  try {
    const session = await request("GET", "/api/session");
    if (session.status === 200 && session.payload && session.payload.role === "ADMIN") {
      await enterAdmin();
      return;
    }
    if (session.status === 200 && session.payload && session.payload.role === "PERSON") {
      await enterPerson(session.payload.personId);
      return;
    }
    showMode("login");
    setStatus("请先登录");
  } catch (failure) {
    showMode("login");
    setStatus(failure.message);
  }
}


// ---------------------------------------------------------------- 页面与非秘密场景配置
const pages = {
  overview: ["世界总览", "同一个确认时点，查看世界状态与待办。"],
  accounts: ["账户资产", "钱归谁、持有什么，估值是否完整。"],
  market: ["成交与分红", "价格来自真实订单，分红来自已确认公司行为。"],
  company: ["公司经营与治理", "经营如何兑现，现金为什么变化，董事会怎样行动。"],
  performance: ["账户表现与历史", "净资产、期间表现与真实资本流，口径唯一。"],
  org: ["人物与任职", "谁能做什么，任职何时生效或结束。"],
  information: ["信息与阅读", "谁发布了什么版本，谁实际读到什么。"],
  engineering: ["公司经营与回款", "制造与两家工程公司各自真实的合同、工单、预算、客户请求与回款。"],
  bank: ["银行审计", "准备金、存款负债、贷款、利息与权益、门槛缺项与托管，取自同一确认快照。"],
  media: ["媒体采编", "选题、审阅、发布与更正，都有实际工时和原始版本。"],
  people: ["人物视角", "以各自的权限观察世界，保留信息边界。"],
  models: ["模型运行", "让人物依据自己的观察完成一次判断，查看实际输入与回报。"],
  settings: ["模型与密钥", "为五类场景配置模型、密钥槽位和调用预算。"],
  interview: ["采访预约", "查看采访预约、逐轮对话、实际阅读与机构工时。"],
  evidence: ["证据与共享", "查看真实证据根、公开标签与实际共享；共享不等于已读。"],
  social: ["消息", "查看真实送达、未送达原因与实际阅读；未送达不显示原因。"],
  groups: ["群组与平台", "审计群成员、邀请、帖子版本与传播来源；人物视角按本人权限单独显示。"],
  history: ["历史回看", "在已确认记录上按位置只读回看：不推进世界、不写日志、不调用模型。"]
};
function selectPage(page, refresh = true) {
  if (!pages[page]) return;
  clearSecrets();
  stopLivePoll();
  if (state.page === "market" && page !== "market") clearMarketDom();
  if (state.page === "media" && page !== "media") clearMediaDom();
  if (state.page === "interview" && page !== "interview") clearInterviewDom();
  if (state.page === "evidence" && page !== "evidence") clearEvidenceDom();
  if (state.page === "social" && page !== "social") clearSocialDom();
  if (state.page === "groups" && page !== "groups") clearGroupsDom();
  if (state.page === "engineering" && page !== "engineering") clearEngineeringDom();
  if (state.page === "performance" && page !== "performance") clearPerformanceHistoryDom();
  if (state.page === "history" && page !== "history") clearHistoryDom();
  state.page = page;
  state.pageVersion += 1;
  $("page-title").textContent = pages[page][0];
  $("page-description").textContent = pages[page][1];
  document.querySelectorAll("[data-panel]").forEach(node => { node.hidden = node.dataset.panel !== page; });
  placeGroupsPanel();
  document.querySelectorAll("[data-page]").forEach(node => {
    if (node.dataset.page === page) node.setAttribute("aria-current", "page");
    else node.removeAttribute("aria-current");
  });
  if (refresh) {
    setStatus("正在读取本页确认状态…");
    guard(refreshAdmin());
  }
}
function routeField(container, scene, name, labelText, value, choices) {
  const group = document.createElement("div");
  const label = document.createElement("label");
  const id = "route-" + scene + "-" + name;
  label.htmlFor = id;
  label.textContent = labelText;
  const control = document.createElement(choices ? "select" : "input");
  control.id = id;
  control.dataset.field = name;
  if (choices) {
    for (const item of choices) {
      const option = document.createElement("option");
      option.value = item;
      option.textContent = item;
      control.appendChild(option);
    }
  } else {
    control.type = "number";
    control.step = "1";
    control.min = name === "maxOutputTokens" ? "128" : name === "maxTotalTokens" ? "256" : "1";
    control.max = ({ maxCalls: 100, maxTotalTokens: 1000000, maxOutputTokens: 8192, maxConcurrency: 4 })[name];
    control.required = true;
  }
  control.value = String(value);
  group.append(label, control);
  container.appendChild(group);
}
async function refreshRoutes(generation, current = () => true) {
  if (!isCurrent(generation) || state.mode !== "admin" || !current()) return;
  const result = await request("GET", "/api/admin/routes");
  if (!isCurrent(generation) || state.mode !== "admin" || !current()) return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) {
    setMessage("routes-result", "场景配置读取失败，请刷新重试。");
    state.routeRevision = null;
    return;
  }
  if (!await refreshCapabilities(generation, current)) return;
  const config = result.payload;
  state.routeRevision = config.revision;
  $("route-provider").textContent = "DeepSeek · " + config.baseUrl + " · 配置版本 " + config.revision;
  $("route-runtime").textContent = config.connected
    ? "当前档案已接入：" + state.capabilities.supportedScenes.map(id => LIVE_SCENES[id] || id).join("、") + "。保存配置不会自动调用；其余场景尚未接入。"
    : "配置可保存，尚未连接世界决策执行器；当前不会因此产生模型调用。";
  $("routes-list").replaceChildren();
  for (const route of config.routes) {
    const card = document.createElement("section");
    card.className = "route-card";
    card.dataset.scene = route.scene;
    const head = document.createElement("div"); head.className = "route-head";
    const title = document.createElement("h3");
    title.textContent = route.sceneLabel || route.scene;
    const enabledLabel = document.createElement("label"); enabledLabel.className = "route-enabled";
    const enabled = document.createElement("input");
    enabled.type = "checkbox"; enabled.dataset.field = "enabled"; enabled.checked = route.enabled;
    enabledLabel.append(enabled, document.createTextNode("允许启用"));
    head.append(title, enabledLabel);
    const fields = document.createElement("div"); fields.className = "route-fields";
    routeField(fields, route.scene, "slotId", "Key 槽位", route.slotId, ["key-1", "key-2", "key-3", "key-4", "key-5"]);
    routeField(fields, route.scene, "model", "模型", route.model, ["deepseek-flash", "deepseek-v4-pro"]);
    routeField(fields, route.scene, "maxCalls", "调用上限", route.maxCalls);
    routeField(fields, route.scene, "maxTotalTokens", "总 token 上限", route.maxTotalTokens);
    routeField(fields, route.scene, "maxOutputTokens", "单次输出上限", route.maxOutputTokens);
    routeField(fields, route.scene, "maxConcurrency", "并发上限", route.maxConcurrency);
    card.append(head, fields); $("routes-list").appendChild(card);
  }
}
async function saveRoutes(event) {
  event.preventDefault();
  if (state.mode !== "admin" || state.routeRevision === null) return;
  const generation = state.generation;
  const routes = Array.from($("routes-list").children, card => {
    const route = { scene: card.dataset.scene };
    card.querySelectorAll("[data-field]").forEach(input => {
      const name = input.dataset.field;
      if (name === "enabled") route[name] = input.checked;
      else if (name.startsWith("max")) {
        const value = Number(input.value);
        if (!Number.isSafeInteger(value) || !/^[1-9][0-9]*$/.test(input.value)) throw new Error("预算必须填写正整数。");
        route[name] = value;
      } else route[name] = input.value;
    });
    return route;
  });
  const result = await request("POST", "/api/admin/routes", { expectedRevision: state.routeRevision, routes });
  if (!isCurrent(generation) || state.mode !== "admin") return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status === 200 || result.status === 409) {
    await refreshRoutes(generation);
    if (!isCurrent(generation) || state.mode !== "admin") return;
    setMessage("routes-result", result.status === 200 ? "配置已保存。" : "配置已在别处修改，已读取最新版本，请重新核对。");
  } else setMessage("routes-result", "未保存（" + result.status + "）。请检查预算范围与配置，原设置保持。");
}
function svgNode(name, attributes, label) {
  const node = document.createElementNS("http://www.w3.org/2000/svg", name);
  for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, String(value));
  if (label !== undefined) node.textContent = label;
  return node;
}
function clearMarketDom() {
  state.marketRequest += 1;
  state.market = null;
  const svg = $("candle-chart");
  if (svg) { svg.replaceChildren(); svg.setAttribute("hidden", ""); }
  if ($("market-symbol")) $("market-symbol").replaceChildren();
  if ($("market-period")) $("market-period").value = "minute";
  for (const id of ["market-cut", "market-summary", "market-status", "market-bars-count",
    "chart-caption", "market-bars-note"]) {
    if ($(id)) $(id).textContent = "";
  }
  if ($("chart-caption")) $("chart-caption").textContent = "尚无真实成交。";
  for (const id of ["market-bars", "market-gaps"]) {
    if ($(id)) fillTable(id, [], () => undefined);
  }
  for (const id of ["market-bars-empty", "market-gaps-empty"]) {
    if ($(id)) $(id).hidden = true;
  }
  setMessage("market-error", "");
}

async function loadMarketHistory(generation = state.generation,
                                 pageVersion = state.pageVersion) {
  if (state.mode !== "admin" || state.page !== "market") return;
  const requestId = ++state.marketRequest;
  const symbol = $("market-symbol").value || "";
  const period = $("market-period").value || "minute";
  setMessage("market-error", "");
  $("market-status").textContent = "正在读取当前确认截止的真实成交…";
  let result;
  try {
    result = await request("POST", "/api/admin/market-history", { symbol, period });
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "admin"
        && pageVersion === state.pageVersion && requestId === state.marketRequest) {
      $("market-status").textContent = "";
      setMessage("market-error", failure.message);
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin"
      || pageVersion !== state.pageVersion || state.page !== "market"
      || requestId !== state.marketRequest
      || ($("market-symbol").value || "") !== symbol
      || ($("market-period").value || "minute") !== period) return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) {
    $("market-status").textContent = "";
    setMessage("market-error", "读取市场历史失败（" + result.status + "），请稍后重试。");
    return;
  }
  $("market-status").textContent = "";
  state.market = result.payload;
  renderMarketHistory(result.payload);
}

function renderMarketHistory(view) {
  $("market-cut").textContent = view.runId + " · 记录 " + view.position
    + " · " + view.timeMillis + " 毫秒";
  const statusText = view.available ? (view.marketClosed ? "撮合已收盘" : "撮合开放")
    : "未启用撮合";
  $("market-summary").textContent = statusText + " · 口径 " + basisLabel(view.basis)
    + " · 有成交桶 " + view.totalBars
    + (view.truncated ? "（仅显示最近 500 根）" : "");
  $("market-bars-count").textContent = String(view.bars.length) + " / " + view.totalBars;

  const symbols = Array.isArray(view.symbols) ? view.symbols : [];
  const select = $("market-symbol");
  const previous = view.selectedSymbol || select.value || "";
  select.replaceChildren();
  for (const symbol of symbols) {
    const option = document.createElement("option");
    option.value = symbol;
    option.textContent = symbol;
    select.appendChild(option);
  }
  if (!symbols.length) {
    const option = document.createElement("option");
    option.value = "";
    option.textContent = "（无真实证券）";
    select.appendChild(option);
  }
  select.value = symbols.includes(previous) ? previous : (symbols[0] || "");
  $("market-period").value = view.period || "minute";

  const svg = $("candle-chart");
  svg.replaceChildren();
  svg.setAttribute("hidden", "");
  fillTable("market-bars", view.bars || [], (tr, row) => {
    cell(tr, row.startMillis, "num");
    cell(tr, row.endMillis, "num");
    cell(tr, row.complete ? "是" : "否");
    cell(tr, row.firstTradeId + " / " + row.lastTradeId, "num");
    cell(tr, row.count, "num");
    cell(tr, row.open, "num");
    cell(tr, row.high, "num");
    cell(tr, row.low, "num");
    cell(tr, row.close, "num");
    cell(tr, row.volume, "num");
    cell(tr, volumeStatusLabel(row.volumeStatus));
    cell(tr, row.amount, "num");
  });
  fillTable("market-gaps", view.gaps || [], (tr, row) => {
    cell(tr, row.fromMillis, "num");
    cell(tr, row.toMillis, "num");
    cell(tr, row.missingBars, "num");
  });

  $("market-bars-empty").hidden = (view.bars || []).length !== 0;
  $("market-bars-empty").textContent = view.available
    ? "此周期尚无真实成交。" : "此运行未启用撮合，不生成成交表。";
  $("market-gaps-empty").hidden = (view.gaps || []).length !== 0;
  $("market-bars-note").textContent = view.available
    ? "只有实际有成交的桶；空档不补价。表格字符串为原始精度。"
    : "此运行没有撮合子集，不能生成价格、成交量或成交额。";

  if (!view.available) {
    $("chart-caption").textContent = "此运行未启用撮合，不生成 K 线。";
    return;
  }
  if (!view.bars || !view.bars.length) {
    $("chart-caption").textContent = "所选证券与周期在当前截止前尚无真实成交。";
    return;
  }
  renderCandleChart(view);
}

function priceToHundredths(value) {
  const raw = String(value === null || value === undefined ? "" : value).trim();
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(raw);
  if (!match) return null;
  const fraction = (match[3] || "").padEnd(2, "0").slice(0, 2);
  const scaled = BigInt(match[2]) * 100n + BigInt(fraction || "0");
  return match[1] === "-" ? -scaled : scaled;
}

function volumeToBigInt(value) {
  if (value === null || value === undefined || value === "") return null;
  try {
    return BigInt(String(value));
  } catch (ignored) {
    return null;
  }
}

function hundredthsToText(value) {
  const negative = value < 0n;
  const absolute = negative ? -value : value;
  const integer = absolute / 100n;
  const fraction = absolute % 100n;
  return (negative ? "-" : "") + integer.toString() + "."
    + fraction.toString().padStart(2, "0");
}

function basisLabel(value) {
  return ({ UNADJUSTED_EXPERIMENT: "原始成交价（未复权）" })[value] || String(value || "—");
}

function volumeStatusLabel(value) {
  return ({ OK: "正常", OVERFLOW: "数量超出整数范围" })[value] || String(value || "—");
}

function renderCandleChart(view) {
  const bars = view.bars || [];
  const svg = $("candle-chart");
  svg.replaceChildren();
  svg.setAttribute("hidden", "");
  if (!bars.length) return;

  const parsed = [];
  let minPrice = null;
  let maxPrice = null;
  let maxVolume = null;
  for (const bar of bars) {
    const open = priceToHundredths(bar.open);
    const high = priceToHundredths(bar.high);
    const low = priceToHundredths(bar.low);
    const close = priceToHundredths(bar.close);
    if (open === null || high === null || low === null || close === null) {
      $("chart-caption").textContent = "价格字符串无法解析，请查看下表精确值。";
      return;
    }
    const volume = volumeToBigInt(bar.volume);
    if (volume !== null) {
      if (maxVolume === null || volume > maxVolume) maxVolume = volume;
    }
    minPrice = minPrice === null || low < minPrice ? low : minPrice;
    maxPrice = maxPrice === null || high > maxPrice ? high : maxPrice;
    parsed.push({ bar, open, high, low, close, volume });
  }
  let span = maxPrice - minPrice;
  if (span === 0n) {
    span = maxPrice === 0n ? 1n : (maxPrice < 0n ? -maxPrice : maxPrice) / 50n;
    if (span === 0n) span = 1n;
  }
  const padding = span / 8n || 1n;
  const lowBound = minPrice - padding;
  const highBound = maxPrice + padding;
  const priceSpan = highBound - lowBound;
  const y = (value) => 272 - Number((value - lowBound) * 230n / priceSpan);

  for (let index = 0; index < 4; index++) {
    const value = lowBound + (highBound - lowBound) * BigInt(index) / 3n;
    const lineY = y(value);
    svg.appendChild(svgNode("line", { x1: 76, y1: lineY, x2: 940, y2: lineY,
      class: "chart-grid" }));
    svg.appendChild(svgNode("text", { x: 66, y: lineY + 4, "text-anchor": "end",
      class: "chart-label" }, hundredthsToText(value)));
  }
  const first = BigInt(bars[0].startMillis);
  const last = BigInt(bars[bars.length - 1].startMillis);
  const timeSpan = last - first;
  const width = Math.max(3, Math.min(16, 800 / Math.max(bars.length, 1) * 0.62));
  for (const item of parsed) {
    const bar = item.bar;
    const start = BigInt(bar.startMillis);
    const offset = timeSpan === 0n ? 480
      : 80 + Number((start - first) * 800n / timeSpan);
    const openY = y(item.open), closeY = y(item.close);
    const highY = y(item.high), lowY = y(item.low);
    const up = item.close >= item.open;
    const stateClass = (up ? "chart-up" : "chart-down")
      + (bar.complete ? "" : " chart-incomplete");
    svg.appendChild(svgNode("line", { x1: offset, y1: highY, x2: offset, y2: lowY,
      class: "chart-wick " + stateClass }));
    const top = Math.min(openY, closeY);
    const body = svgNode("rect", { x: offset - width / 2, y: top, width,
      height: Math.max(1, Math.abs(openY - closeY)),
      class: "chart-body " + stateClass });
    body.appendChild(svgNode("title", {}, bar.startMillis + "–"
      + (bar.endMillis === null ? "溢出" : bar.endMillis) + " 毫秒 · "
      + (bar.complete ? "已完成" : "未完成") + " · 开 " + bar.open + " · 高 " + bar.high
      + " · 低 " + bar.low + " · 收 " + bar.close + " · 量 "
      + (bar.volume === null ? "未知（数量超出整数范围）" : bar.volume)
      + " · 额 " + bar.amount));
    svg.appendChild(body);
  }

  svg.appendChild(svgNode("line", { x1: 76, y1: 390, x2: 940, y2: 390,
    class: "chart-grid" }));
  svg.appendChild(svgNode("text", { x: 66, y: 394, "text-anchor": "end",
    class: "chart-label" }, "成交量"));
  for (const item of parsed) {
    const bar = item.bar;
    const start = BigInt(bar.startMillis);
    const offset = timeSpan === 0n ? 480
      : 80 + Number((start - first) * 800n / timeSpan);
    if (item.volume === null) {
      const unknown = svgNode("rect", { x: offset - width / 2, y: 378, width,
        height: 12, class: "chart-volume chart-volume-unknown" });
      unknown.appendChild(svgNode("title", {}, "成交量未知（数量超出整数范围）"));
      svg.appendChild(unknown);
      continue;
    }
    const height = maxVolume === null || maxVolume === 0n ? 1
      : Math.max(1, Number(item.volume * 70n / maxVolume));
    const volumeBar = svgNode("rect", { x: offset - width / 2, y: 390 - height, width,
      height, class: "chart-volume" });
    volumeBar.appendChild(svgNode("title", {}, "成交量 " + bar.volume + " 股"));
    svg.appendChild(volumeBar);
  }

  svg.appendChild(svgNode("text", { x: 80, y: 414, class: "chart-label" },
    String(first)));
  svg.appendChild(svgNode("text", { x: 940, y: 414, "text-anchor": "end",
    class: "chart-label" }, String(last)));
  svg.appendChild(svgNode("text", { x: 80, y: 426, class: "chart-label" },
    "虚线 = 未完成柱；成交量柱高为近似坐标，精确值见下表；量溢出显示未知"));
  svg.setAttribute("aria-label", (view.selectedSymbol || "证券") + " "
    + view.period + " 真实成交 OHLC 与成交量，共 " + bars.length + " 根；精确值见下方表格");
  svg.removeAttribute("hidden");
  $("chart-caption").textContent = (view.selectedSymbol || "证券") + " · "
    + ({ minute: "分钟", day: "模拟日", week: "模拟周" }[view.period] || view.period)
    + " · 显示最近 " + bars.length + " / 共 " + view.totalBars
    + " 个有成交桶 · 纵轴：元；横轴：模拟毫秒（近似坐标）";
}

// ---------------------------------------------------------------- 绑定

function guard(promise) {
  promise.catch((failure) => {
    if (state.mode === "admin") {
      setMessage("admin-error", failure && failure.message ? failure.message : "操作失败");
    } else if (state.mode === "person") {
      setMessage("person-error", failure && failure.message ? failure.message : "操作失败");
    } else {
      setMessage("login-error", failure && failure.message ? failure.message : "操作失败");
    }
  });
}

// ---------------------------------------------------------------- 历史回看（上帝只读）
// 只读约束：本页的任何异步分支都不得调用 /api/admin/advance、模型、阅读或回忆入口；
// 迟到的历史响应必须同时满足身份代次、页面代次与所选请求号，否则一律丢弃。

/** 历史回看的本地状态；离开页面时整体清空。 */
function historyState() {
  if (!state.history) {
    state.history = { after: 0, limit: 50, cut: null, entries: [], selected: null,
      request: 0, exportRequest: 0 };
  }
  return state.history;
}

/** 清空历史页 DOM 与本地状态（切页、切身份、退出时调用）。 */
function clearHistoryDom() {
  state.history = { after: 0, limit: 50, cut: null, entries: [], selected: null,
    request: 0, exportRequest: 0 };
  ["history-run", "history-cut", "history-run-pos", "history-position", "history-position-cut",
    "history-time", "history-phase"].forEach(id => { $(id).textContent = "—"; });
  ["history-count", "history-checkpoint-status", "history-verify-result", "history-note",
    "history-unsupported"].forEach(id => { $(id).textContent = ""; });
  // 导出上下文属于本页：离页/切身份后不得留下上一身份的检查点正文。
  $("history-checkpoint-text").value = "";
  $("history-mode").textContent = "选择某个已确认位置后只读回看。";
  setMessage("history-error", "");
  fillTable("history-index", [], () => undefined);
  fillTable("history-overview", [], () => undefined);
  fillTable("history-accounts", [], () => undefined);
  fillTable("history-securities", [], () => undefined);
  fillTable("history-pending", [], () => undefined);
  fillTable("history-roles", [], () => undefined);
  fillTable("history-information", [], () => undefined);
  fillTable("history-engineering", [], () => undefined);
  fillTable("history-groups", [], () => undefined);
  clearBranchDom();
}

/** 读取一个严格非负整数字段；非法时给出可见提示并返回 null。 */
function historyIntField(id, minimum, message) {
  const raw = String($(id).value || "").trim();
  if (!/^[0-9]{1,18}$/.test(raw)) {
    setMessage("history-error", message);
    return null;
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < minimum) {
    setMessage("history-error", message);
    return null;
  }
  return value;
}

/** 当前输入框里的确认位置；非法时返回 null。 */
function historyPositionValue() {
  return historyIntField("history-position-input", 1, "确认位置必须是大于 0 的整数。");
}

/** 读取历史索引页（只读）。 */
async function loadHistoryIndex() {
  const after = historyIntField("history-after", 0, "起始位置必须是非负整数。");
  if (after === null) return;
  const limit = historyIntField("history-limit", 1, "每页条数必须是正整数。");
  if (limit === null) return;
  const history = historyState();
  history.after = after;
  history.limit = Math.min(limit, 200);
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  const current = () => isCurrent(generation) && state.mode === "admin"
    && state.page === "history" && pageVersion === state.pageVersion;
  let result;
  try {
    result = await request("GET",
      "/api/admin/history?after=" + history.after + "&limit=" + history.limit);
  } catch (failure) {
    if (current()) setMessage("history-error", failure.message);
    return;
  }
  if (!current()) return;
  if (result.status === 401 || result.status === 403) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    setMessage("history-error", "读取历史索引失败（" + result.status + "）。");
    return;
  }
  setMessage("history-error", "");
  renderHistoryIndex(result.payload);
}

/** 渲染历史索引（位置为精确字符串）。 */
function renderHistoryIndex(view) {
  const history = historyState();
  history.cut = Number(view.cutPosition);
  history.entries = view.entries || [];
  $("history-run").textContent = view.runId || "—";
  $("history-cut").textContent = view.cutPosition;
  $("history-count").textContent = history.entries.length + " 条（每页上限 200）";
  fillTable("history-index", history.entries, (tr, row) => {
    cell(tr, row.position);
    cell(tr, row.commandId);
    cell(tr, row.result);
    cell(tr, row.eventCount);
    const td = document.createElement("td");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "回看";
    button.addEventListener("click", () => guard(openHistoryPosition(Number(row.position))));
    td.appendChild(button);
    tr.appendChild(td);
  });
  show("history-index-empty", history.entries.length === 0);
}

/** 打开某个确认位置的只读历史视图。 */
async function openHistoryPosition(position) {
  if (!Number.isSafeInteger(position) || position < 1) {
    setMessage("history-error", "请先选择一个有效的确认位置。");
    return;
  }
  const history = historyState();
  const requestId = ++history.request;
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  const current = () => isCurrent(generation) && state.mode === "admin"
    && state.page === "history" && pageVersion === state.pageVersion
    && requestId === history.request;
  $("history-mode").textContent = "正在读取位置 " + position + "…";
  let result;
  try {
    result = await request("GET", "/api/admin/history/view?position=" + position);
  } catch (failure) {
    if (current()) setMessage("history-error", failure.message);
    return;
  }
  if (!current()) return;
  if (result.status === 401 || result.status === 403) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    setMessage("history-error", "读取位置 " + position + " 失败（" + result.status + "）。");
    $("history-mode").textContent = "该位置没有可显示的历史帧。";
    return;
  }
  setMessage("history-error", "");
  history.selected = position;
  renderHistoryView(result.payload);
}

/** 历史总览字段顺序（固定，避免对象键序漂移）。 */
const HISTORY_OVERVIEW_FIELDS = [
  ["runId", "运行"], ["position", "确认位置"], ["cutPosition", "读取时确认截止"],
  ["timeMillis", "模拟时点（毫秒）"], ["lastPhase", "最后阶段"], ["accounts", "账户数"],
  ["totalCash", "现金合计（元）"], ["pending", "待办数"], ["delivered", "已投递回报"],
  ["usedTaskIds", "已用任务标识"], ["modules", "已启用模块"]
];

/** 渲染固定位置的历史视图；全部分区来自服务端同一份快照。 */
function renderHistoryView(view) {
  $("history-run-pos").textContent = view.runId || "—";
  $("history-position").textContent = view.position;
  $("history-position-cut").textContent = view.cutPosition;
  $("history-time").textContent = view.timeMillis;
  $("history-phase").textContent = view.lastPhase || "—";
  $("history-mode").textContent = view.historical
    ? "回看模式：位置 " + view.position + " 早于当前确认截止 " + view.cutPosition + "。"
    : "回看模式：位置 " + view.position + " 就是当前确认截止。";
  $("history-note").textContent = (view.notes || []).join(" ");
  $("history-unsupported").textContent = (view.unsupported || []).join(" ");

  const overview = (view.overview && view.overview.rows && view.overview.rows[0]) || {};
  fillTable("history-overview", HISTORY_OVERVIEW_FIELDS, (tr, field) => {
    cell(tr, field[1]);
    cell(tr, overview[field[0]] === undefined ? "" : overview[field[0]]);
  });

  const rows = panel => (panel && panel.rows) || [];
  fillTable("history-accounts", rows(view.accounts), (tr, row) => {
    cell(tr, row.accountId); cell(tr, row.ownerId); cell(tr, row.cash); cell(tr, row.source);
  });
  show("history-accounts-empty", rows(view.accounts).length === 0);
  fillTable("history-securities", rows(view.securities), (tr, row) => {
    cell(tr, row.kind); cell(tr, row.lotId); cell(tr, row.accountId); cell(tr, row.symbol);
    cell(tr, row.remaining); cell(tr, row.costPrice === undefined ? "" : row.costPrice);
    cell(tr, row.acquiredPosition === undefined ? "" : row.acquiredPosition);
  });
  show("history-securities-empty", rows(view.securities).length === 0);
  fillTable("history-pending", rows(view.pending), (tr, row) => {
    cell(tr, row.taskId); cell(tr, row.dueTimeMillis); cell(tr, row.phase); cell(tr, row.kind);
  });
  show("history-pending-empty", rows(view.pending).length === 0);
  fillTable("history-roles", rows(view.roles), (tr, row) => {
    cell(tr, row.appointmentId); cell(tr, row.personPartyId); cell(tr, row.organizationPartyId);
    cell(tr, row.role); cell(tr, row.startsAt); cell(tr, row.endsAt); cell(tr, row.revokedAt);
  });
  show("history-roles-empty", rows(view.roles).length === 0);
  fillTable("history-information", rows(view.information), (tr, row) => {
    cell(tr, row.docId); cell(tr, row.publisherPartyId); cell(tr, row.audience);
    cell(tr, row.version); cell(tr, row.title); cell(tr, row.publishedAtMillis);
    cell(tr, row.readDurationMillis);
  });
  show("history-information-empty", rows(view.information).length === 0);
  fillTable("history-engineering", rows(view.engineering), (tr, row) => {
    cell(tr, row.companyPartyId); cell(tr, row.contracts); cell(tr, row.workOrders);
    cell(tr, row.claims); cell(tr, row.payable); cell(tr, row.hasBoard);
  });
  show("history-engineering-empty", rows(view.engineering).length === 0);
  fillTable("history-groups", rows(view.groups), (tr, row) => {
    cell(tr, row.groupId); cell(tr, row.ownerPartyId); cell(tr, row.joinPolicy);
    cell(tr, row.historyPolicy); cell(tr, row.createdAt); cell(tr, row.openMembers);
    cell(tr, row.posts);
  });
  show("history-groups-empty", rows(view.groups).length === 0);
}

/** 取回检查点信封原文（保持服务端字节，不重新序列化）。 */
async function fetchHistoryCheckpoint(position) {
  const response = await fetch("/api/admin/history/checkpoint?position=" + position,
    { credentials: "same-origin" });
  if (response.status !== 200) {
    return { status: response.status, text: null };
  }
  return { status: 200, text: await response.text() };
}

/**
 * 导出检查点：位置以**当前成功显示的确认帧**为准。
 *
 * <p>没有成功显示的历史帧时先要求回看，输入框不能另行改变导出对象。</p>
 */

async function downloadHistoryCheckpoint() {
  const history = historyState();
  const displayed = Number.isSafeInteger(history.selected) && history.selected > 0
    ? history.selected : null;
  const position = displayed;
  if (position === null) {
    $("history-checkpoint-status").textContent = "请先回看一个已确认位置，再导出检查点。";
    return;
  }
  const exportRequest = ++history.exportRequest;
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  const current = () => state.history === history && isCurrent(generation)
    && state.mode === "admin" && state.page === "history"
    && pageVersion === state.pageVersion && exportRequest === history.exportRequest
    && history.selected === displayed;
  $("history-checkpoint-status").textContent = "正在导出显示中的位置 " + position + "…";
  let result;
  try {
    result = await fetchHistoryCheckpoint(position);
  } catch (failure) {
    if (current()) $("history-checkpoint-status").textContent = failure.message;
    return;
  }
  if (!current()) {
    // 离页、切身份、换位置或又发了一次导出：迟到的回包不写文本、不触发下载。
    return;
  }
  if (result.status !== 200 || !result.text) {
    $("history-checkpoint-status").textContent = "导出失败（" + result.status + "）。";
    return;
  }
  let envelope = null;
  try {
    envelope = JSON.parse(result.text);
  } catch (ignored) {
    envelope = null;
  }
  if (!envelope || String(envelope.position) !== String(position)) {
    $("history-checkpoint-status").textContent = "导出信封与所选位置不一致，已拒绝落盘。";
    return;
  }
  $("history-checkpoint-text").value = result.text;
  $("history-checkpoint-status").textContent =
    "已导出位置 " + position + "（" + result.text.length + " 字符）";
  const blob = new Blob([result.text], { type: "application/json" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = "world-checkpoint-" + position + ".json";
  link.click();
  URL.revokeObjectURL(link.href);
}

/** 核验一份检查点信封：只与当前运行同一确认批次对照。 */
async function verifyHistoryCheckpointText(text) {
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  const current = () => isCurrent(generation) && state.mode === "admin"
    && state.page === "history" && pageVersion === state.pageVersion;
  let envelope;
  try {
    envelope = JSON.parse(String(text || ""));
  } catch (failure) {
    $("history-verify-result").textContent = "这不是合法 JSON。";
    return;
  }
  $("history-verify-result").textContent = "正在核验…";
  let result;
  try {
    result = await request("POST", "/api/admin/history/verify", envelope);
  } catch (failure) {
    if (current()) $("history-verify-result").textContent = failure.message;
    return;
  }
  if (!current()) return;
  if (result.status === 401 || result.status === 403) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    $("history-verify-result").textContent = "核验请求失败（" + result.status + "）。";
    return;
  }
  const verdict = result.payload;
  $("history-verify-result").textContent = verdict.ok
    ? "核验通过：运行 " + verdict.runId + " 位置 " + verdict.position + " 与本地已确认批次一致。"
    : "核验未通过：" + verdict.reason + "（位置 " + verdict.position + "）。";
}

/** 分支运行的稳定原因码 → 人话；未知码原样显示，不猜。 */
const BRANCH_REASONS = {
  BRANCH_POSITION_UNCONFIRMED: "该位置还没有确认",
  BRANCH_BARRIER_PENDING: "该位置仍有未解决的决定",
  BRANCH_ATTEMPT_IN_FLIGHT: "该位置仍有未结算的尝试",
  BRANCH_FUTURE_REFERENCE: "存在晚于切点的决定或派发引用",
  BRANCH_PARENT_IS_BRANCH: "父运行本身是分支（只允许一层）",
  BRANCH_PARENT_SCAN_UNREADABLE: "该位置的父档记录无法读取"
};

/** 分支运行页的本地状态；离页或切身份时整体清空。 */
function branchState() {
  if (!state.branch) {
    state.branch = { support: null, runs: [], current: null, launcher: false, active: 0, max: 0,
      request: 0, supportRequest: 0, actionRequest: 0 };
  }
  return state.branch;
}

/** 清空分支运行页 DOM 与本地状态（切页、切身份、退出时调用）。 */
function clearBranchDom() {
  state.branch = null;
  ["branch-run", "branch-parent", "branch-parent-position", "branch-position", "branch-active"]
    .forEach(id => { $(id).textContent = "—"; });
  ["branch-support", "branch-status"].forEach(id => { $(id).textContent = ""; });
  $("branch-note").hidden = true;
  $("branch-note").textContent = "";
  setMessage("branch-error", "");
  $("branch-create").disabled = true;
  fillTable("branch-runs", [], () => undefined);
  show("branch-runs-empty", false);
}

/** 读取当前运行来源与本进程管理的子运行真实状态。 */
async function loadBranchRuns() {
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  const branch = branchState();
  const requestId = ++branch.request;
  const current = () => isCurrent(generation) && state.mode === "admin"
    && state.page === "history" && pageVersion === state.pageVersion
    && requestId === branch.request;
  let result;
  try {
    result = await request("GET", "/api/admin/runs");
  } catch (failure) {
    if (current()) setMessage("branch-error", failure.message);
    return;
  }
  if (!current()) return;
  if (result.status === 401 || result.status === 403) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    setMessage("branch-error", "读取运行清单失败（" + result.status + "）。");
    return;
  }
  setMessage("branch-error", "");
  renderBranchRuns(result.payload);
}

/** 渲染运行清单；每个按钮的可用性都来自服务端状态，不做本地乐观判断。 */
function renderBranchRuns(view) {
  const branch = branchState();
  branch.current = view.current || null;
  branch.runs = view.runs || [];
  branch.launcher = !!view.launcher;
  branch.active = Number(view.active || 0);
  branch.max = Number(view.maxRuns || 0);
  const run = branch.current || {};
  $("branch-run").textContent = run.runId || "—";
  $("branch-parent").textContent = run.parentRunId ? run.parentRunId : "本运行是父运行";
  $("branch-parent-position").textContent = run.parentRunId ? String(run.parentPosition) : "—";
  $("branch-position").textContent = run.position || "—";
  $("branch-active").textContent = branch.launcher
    ? branch.active + " / " + branch.max + " 个运行中"
    : "本进程没有启动器（不能启动/进入）";
  if (run.parentRunId) {
    $("branch-note").hidden = false;
    $("branch-note").textContent = "本运行由 " + run.parentRunId + " 在位置 " + run.parentPosition
      + " 派生；位置 1 到 " + run.parentPosition + " 是父运行原帧，不是本运行的检查点。";
  } else {
    $("branch-note").hidden = true;
    $("branch-note").textContent = "";
  }
  fillTable("branch-runs", branch.runs, (tr, row) => {
    cell(tr, row.runId);
    cell(tr, row.parentRunId || "—");
    cell(tr, row.parentPosition);
    cell(tr, row.firstPosition);
    cell(tr, row.status);
    cell(tr, row.port > 0 ? row.port : "—");
    cell(tr, row.reason || "");
    const td = document.createElement("td");
    const running = row.status === "RUNNING" || row.status === "STARTING";
    const start = document.createElement("button");
    start.type = "button";
    start.textContent = "启动";
    start.disabled = running || !branch.launcher || branch.active >= branch.max;
    start.title = branch.launcher ? "" : "本进程没有启动器";
    start.addEventListener("click", () => guard(runBranchAction(row.runId, "start")));
    td.appendChild(start);
    const enter = document.createElement("button");
    enter.type = "button";
    enter.textContent = "进入";
    enter.disabled = row.status !== "RUNNING";
    enter.title = row.status === "RUNNING" ? "" : "该运行没有成功启动，不能进入";
    enter.addEventListener("click", () => guard(runBranchAction(row.runId, "enter")));
    td.appendChild(enter);
    const stop = document.createElement("button");
    stop.type = "button";
    stop.textContent = "停止";
    stop.disabled = !running || !row.managed;
    stop.title = row.managed ? "" : "该运行不是本进程启动的，不按端口终止陌生进程";
    stop.addEventListener("click", () => guard(runBranchAction(row.runId, "stop")));
    td.appendChild(stop);
    tr.appendChild(td);
  });
  show("branch-runs-empty", branch.runs.length === 0);
}

/** 只读检查某个确认位置能否派生；结论来自后端同一谓词。 */
async function checkBranchSupport() {
  const raw = String($("branch-cut").value || "").trim();
  if (!/^[0-9]{1,18}$/.test(raw) || Number(raw) < 1) {
    setMessage("branch-error", "切点位置必须是大于 0 的整数。");
    $("branch-create").disabled = true;
    $("branch-support").textContent = "";
    return;
  }
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  const branch = branchState();
  const requestId = ++branch.supportRequest;
  // 每一次检查都只认"这一次请求 + 这一个选中位置"：旧位置的慢回包不得覆盖新位置的结果。
  const current = () => isCurrent(generation) && state.mode === "admin"
    && state.page === "history" && pageVersion === state.pageVersion
    && state.branch === branch && requestId === branch.supportRequest
    && String($("branch-cut").value || "").trim() === raw;
  setMessage("branch-error", "");
  let result;
  try {
    result = await request("GET", "/api/admin/branches?position=" + raw);
  } catch (failure) {
    if (current()) setMessage("branch-error", failure.message);
    return;
  }
  if (!current()) return;
  if (result.status === 401 || result.status === 403) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    branch.support = null;
    $("branch-create").disabled = true;
    $("branch-support").textContent = "";
    setMessage("branch-error", "检查切点失败（" + result.status + "）。");
    return;
  }
  const view = result.payload;
  // 回包自带的位置必须就是这次要查的位置；串包一律丢弃，不写按钮状态。
  if (String(view.position) !== raw) return;
  branch.support = view;
  if (view.supported) {
    $("branch-support").textContent = "位置 " + view.position + " 可以切（当前确认到 "
      + view.cutPosition + "）。";
  } else {
    const reasons = (view.reasons || []).map(code => BRANCH_REASONS[code] || code);
    $("branch-support").textContent = "位置 " + view.position + " 不能切："
      + (reasons.length ? reasons.join("；") : "未给出原因码");
  }
  $("branch-create").disabled = !view.supported;
}

/** 创建子档：只落子档与索引，不启动进程。 */
async function createBranchRun() {
  const position = String($("branch-cut").value || "").trim();
  const childRunId = String($("branch-child-id").value || "").trim();
  const kind = $("branch-kind").value;
  const note = String($("branch-note-input").value || "").trim();
  if (!/^[0-9]{1,18}$/.test(position) || Number(position) < 1) {
    setMessage("branch-error", "请先填写有效切点位置。");
    return;
  }
  if (!/^[a-z][a-z0-9-]{2,31}$/.test(childRunId)) {
    setMessage("branch-error", "运行标识必须全小写：字母开头，只含小写字母、数字与连字符，3 到 32 位。");
    return;
  }
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  const branch = branchState();
  // 创建只能绑定"当前选中位置 + 该位置刚刚核过的支持结果"，不允许拿旧结论去创另一个位置。
  const checked = branch.support;
  if (!checked || String(checked.position) !== position || !checked.supported) {
    setMessage("branch-error", "请先对位置 " + position + " 检查一次切分可行性"
      + "（当前已核位置：" + (checked && checked.position ? checked.position : "无") + "）。");
    return;
  }
  const requestId = ++branch.actionRequest;
  const current = () => isCurrent(generation) && state.mode === "admin"
    && state.page === "history" && pageVersion === state.pageVersion
    && state.branch === branch && requestId === branch.actionRequest;
  $("branch-status").textContent = "正在创建 " + childRunId + "…";
  setMessage("branch-error", "");
  let result;
  try {
    result = await request("POST", "/api/admin/branches",
      { position, childRunId, kind, note });
  } catch (failure) {
    if (current()) {
      $("branch-status").textContent = "";
      setMessage("branch-error", failure.message);
    }
    return;
  }
  if (!current()) return;
  if (result.status === 401 || result.status === 403) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    $("branch-status").textContent = "";
    const payload = result.payload || {};
    setMessage("branch-error", (payload.code ? payload.code + "：" : "")
      + (payload.message || "创建失败（" + result.status + "）"));
    await loadBranchRuns();
    return;
  }
  $("branch-child-id").value = "";
  $("branch-status").textContent = "已创建 " + result.payload.runId + "（首帧位置 "
    + result.payload.firstPosition + "），尚未启动。";
  await loadBranchRuns();
}

/** 撤掉某个已过期动作留下的"正在…"提示；若已被更新的动作替换则不动。 */
function clearStaleProgress(pendingText, branch, requestId) {
  if (state.branch === branch && branch.actionRequest === requestId
      && pendingText && $("branch-status").textContent === pendingText) {
    $("branch-status").textContent = "";
  }
}

/** 启动 / 停止 / 进入：结果只认服务端返回，失败给真实原因。 */
async function runBranchAction(runId, action) {
  const labels = { start: "启动", stop: "停止", enter: "进入" };
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  const branch = branchState();
  const requestId = ++branch.actionRequest;
  // 动作回包只在"同一身份、同一页、同一份分支状态、且我是最后一个动作"时才算数：
  // 旧代次的 start/enter 回包既不能写状态，也不能把浏览器跳去旧子进程。
  const current = () => isCurrent(generation) && state.mode === "admin"
    && state.page === "history" && pageVersion === state.pageVersion
    && state.branch === branch && requestId === branch.actionRequest;
  setMessage("branch-error", "");
  const pendingText = "正在" + labels[action] + " " + runId + "…";
  $("branch-status").textContent = pendingText;
  let result;
  try {
    result = await request("POST",
      "/api/admin/runs/" + encodeURIComponent(runId) + "/" + action, {});
  } catch (failure) {
    if (current()) {
      $("branch-status").textContent = "";
      setMessage("branch-error", failure.message);
    } else {
      clearStaleProgress(pendingText, branch, requestId);
    }
    return;
  }
  if (!current()) {
    // 过期：不写结果、不导航；只撤掉自己留下的"正在…"（若已被新动作替换则不动）。
    clearStaleProgress(pendingText, branch, requestId);
    return;
  }
  if (result.status === 401 || result.status === 403) {
    sessionLost();
    return;
  }
  if (action === "enter" && result.status === 200 && result.payload
      && result.payload.location) {
    $("branch-status").textContent = "正在切换到 " + runId + "…";
    window.location.assign(result.payload.location);
    return;
  }
  if (result.status !== 200) {
    $("branch-status").textContent = "";
    const payload = result.payload || {};
    setMessage("branch-error", (payload.code ? payload.code + "：" : "")
      + (payload.message || labels[action] + "失败（" + result.status + "）"));
    await loadBranchRuns();
    return;
  }
  $("branch-status").textContent = labels[action] + "完成：" + runId + "（"
    + (result.payload && result.payload.status ? result.payload.status : "已更新") + "）";
  await loadBranchRuns();
}

$("login-form").addEventListener("submit", (event) => guard(login(event)));
$("advance-form").addEventListener("submit", (event) => {
  event.preventDefault();
  guard(advance());
});
$("person-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const personId = $("person-id").value.trim();
  if (!personId) {
    setMessage("admin-error", "请输入人物标识。");
    return;
  }
  guard(switchPerson(personId));
});
$("logout").addEventListener("click", () => guard(logout()));
$("back-admin").addEventListener("click", () => guard(backToAdmin()));

document.querySelectorAll("[data-page]").forEach(button => {
  button.addEventListener("click", () => selectPage(button.dataset.page));
});
$("routes-form").addEventListener("submit", event => guard(saveRoutes(event)));
$("history-load").addEventListener("click", () => guard(loadHistoryIndex()));
$("history-open").addEventListener("click", () => guard(openHistoryPosition(historyPositionValue())));
$("history-live").addEventListener("click", () => selectPage("overview"));
$("history-checkpoint").addEventListener("click", () => guard(downloadHistoryCheckpoint()));
$("history-verify").addEventListener("click",
  () => guard(verifyHistoryCheckpointText($("history-checkpoint-text").value)));
$("branch-check").addEventListener("click", () => guard(checkBranchSupport()));
$("branch-create").addEventListener("click", () => guard(createBranchRun()));
$("refresh").addEventListener("click", () => guard(state.mode === "admin" ? refreshAdmin() : refreshPerson()));
$("market-symbol").addEventListener("change", () => guard(loadMarketHistory()));
$("market-period").addEventListener("change", () => guard(loadMarketHistory()));
$("market-refresh").addEventListener("click", () => guard(loadMarketHistory()));
// ---------------------------------------------------------------- 管理详情页（公司 / 表现 / 人物任职 / 信息阅读）

const LABELS = {
  topic: { BUDGET: "预算授权", OPERATING_REVIEW: "经营考核", DIVIDEND_AUTHORIZATION: "分红授权" },
  meeting: { CONVENED: "已召集", MATERIALS_FROZEN: "材料已冻结", REVIEWED: "已评分", VOTING: "表决中",
    ADOPTED: "已通过", REJECTED: "未通过", ADJOURNED_NO_QUORUM: "不足法定人数",
    IMPLEMENTED: "已实施", IMPLEMENTATION_FAILED: "实施失败" },
  order: { ORDERED: "已下单", RECEIVED: "已到货", FAILED_NO_CASH: "现金不足未成交" },
  batch: { IN_PROGRESS: "在制", FINISHED: "已完工" },
  receivable: { OPEN: "未到期", OVERDUE: "逾期未收", COLLECTED: "已收清" },
  review: { COMPLETE: "证据齐全", INCOMPLETE: "证据不全" },
  valuation: { COMPLETE: "完整", MISSING_PRICE: "缺少价格", UNSUPPORTED_COMPANY_SCOPE: "组织不支持" },
  performance: { COMPLETE: "完整", INCOMPLETE_VALUATION: "估值不完整", NON_POSITIVE_EQUITY: "非正权益",
    NO_PERIOD: "不足两期", UNAVAILABLE: "不可用", NOT_COMPUTED: "未计算" },
  appointment: { ACTIVE: "在任", REVOKED: "已撤销", EXPIRED: "已到期", SCHEDULED: "未开始" },
  reading: { READING: "进行中", COMPLETED: "已完成", CANCELLED: "已取消", DENIED: "复核拒绝" },
  audience: { PUBLIC: "公开", ORGANIZATION: "组织档案", DIRECT: "直投" },
  role: { RESEARCHER: "研究员", PORTFOLIO_MANAGER: "基金经理", RISK_OFFICER: "风控", EXECUTION_TRADER: "执行交易员" },
  ownerKind: { PERSON: "个人", ORGANIZATION: "组织", PRODUCT: "产品", AGGREGATE: "聚合外部" },
  partyKind: { PERSON: "人物", ORGANIZATION: "组织" },
  dimension: { OPERATING_COMMITMENT: "经营兑现", CASH_SOLVENCY: "现金偿债", RISK: "风险",
    DISCLOSURE: "披露", LONG_TERM_INVESTMENT: "长期投入" },
  priceSource: { TRADE: "实际成交", OPENING_REFERENCE: "开局参考", EX_DIVIDEND_REFERENCE: "除息参考", MISSING: "缺价" },
  category: { PROCUREMENT: "采购", PRODUCTION_LABOR: "生产人工" },
  vote: { YES: "赞成", NO: "反对", ABSTAIN: "弃权" },
  commitment: { ACTIVE: "未结", CLOSED: "已结清" }
};

function label(group, value) {
  if (value === null || value === undefined || value === "") return "—";
  const table = LABELS[group];
  return (table && table[value]) || String(value);
}

function clearDetailDom() {
  clearMediaDom();
  clearInterviewDom();
  clearEvidenceDom();
  clearSocialDom();
  clearEngineeringDom();
  state.details = {};
  clearRecall();
  clearBeliefs();
  clearPerformanceHistoryDom();
  $("recall-person").replaceChildren();
  for (const id of DETAIL_TABLES) {
    fillTable(id, [], () => undefined);
  }
  document.querySelectorAll("#admin .count").forEach(node => { node.textContent = ""; });
  for (const id of ["company-cut", "company-report-note", "company-orders-count",
    "company-batches-count", "company-contracts-count", "company-receivables-count",
    "company-bills-count", "company-meetings-count", "performance-cut",
    "performance-holdings-count", "performance-flows-count", "org-cut",
    "org-reports-count", "information-cut", "information-documents-count",
    "information-active-count", "information-observations-count",
    "information-reports-count", "bank-cut", "bank-digest", "bank-missing",
    "bank-custody-note", "bank-unavailable", "bank-totals-note"]) {
    if ($(id)) $(id).textContent = "";
  }
  for (const id of ["company-rules", "company-charter", "company-notes", "performance-notes",
    "org-notes", "information-notes", "bank-archive", "bank-notes", "bank-headline"]) {
    if ($(id)) $(id).replaceChildren();
  }
  for (const id of ["company-report-filter", "performance-filter",
    "org-appointment-filter", "information-closed-filter"]) {
    if ($(id)) $(id).value = "ALL";
  }
  for (const id of ["performance-history-start", "performance-history-end"]) {
    if ($(id)) $(id).value = "";
  }
  if ($("performance-history-owner")) $("performance-history-owner").replaceChildren();
  document.querySelectorAll("#admin .empty").forEach(node => { node.hidden = true; });
}

async function loadDetail(page) {
  if (page === "media") clearMediaDom();
  const url = DETAIL_PAGES[page];
  if (!url || state.mode !== "admin") return;
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  let result;
  try {
    result = await request("GET", url);
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "admin" && pageVersion === state.pageVersion) setMessage("admin-error", failure.message);
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin" || pageVersion !== state.pageVersion || page !== state.page) return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) {
    setMessage("admin-error", "读取" + pages[page][0] + "失败（" + result.status + "），请稍后重试。");
    return;
  }
  state.details[page] = result.payload;
  renderDetail(page, result.payload);
  if (page === "interview") await loadDialogue(generation, pageVersion);
}

function renderDetail(page, view) {
  if (page === state.page) renderAdminCut(view);
  if (page === "company") renderCompany(view);
  else if (page === "media") renderMedia(view);
  else if (page === "interview") renderInterviewAudit(view);
  else if (page === "performance") renderPerformance(view);
  else if (page === "org") renderPeopleAdmin(view);
  else if (page === "information") renderInformation(view);
  else if (page === "bank") renderBank(view);
}

function renderBank(view) {
  const available = view && view.available === true;
  $("bank-cut").textContent = view
    ? "同一确认快照 · 运行 " + text(view.runId) + " · 位置 " + text(view.position)
      + " · 时点 " + text(view.timeMillis) + " 毫秒 · 阶段 " + text(view.phase)
    : "";
  $("bank-digest").textContent = available && view.digest
    ? "状态摘要（sha256）：" + view.digest + " · 配置指纹：" + text(view.configFingerprint) : "";
  setMessage("bank-unavailable", available ? "" : "本档未启用银行模块，不显示零余额银行。"
    + (view && view.reason ? "原因：" + view.reason : ""));

  const archive = (view && view.archive) || {};
  fillDl("bank-archive", available ? [
    ["规则档案", archive.archiveId],
    ["版本", archive.version],
    ["标签", archive.label],
    ["覆盖规则", archive.coverageRule],
    ["声明指标", (archive.required || []).join("、")],
    ["已实现", (archive.implemented || []).join("、")],
    ["未覆盖项", (archive.omitted || []).join("、") || "无声明"],
    ["来源状态", archive.provenanceStatus],
    ["口径", archive.experimentOnly ? "实验规则（非监管合规，非现实校准）" : "—"],
    ["配置指纹", view.configFingerprint],
    ["结算背书（元）", view.backing]
  ] : []);
  const notes = $("bank-notes");
  notes.replaceChildren();
  for (const note of (view && view.notes) || []) {
    const li = document.createElement("li");
    li.textContent = note;
    notes.appendChild(li);
  }

  const banks = (view && view.banks) || [];
  const first = banks[0];
  const firstTotals = (first && first.totals) || {};
  fillDl("bank-headline", available && first ? [
    ["银行", first.bankKey],
    ["准备金（元）", first.reserve],
    ["客户存款负债（元）", first.depositsTotal],
    ["贷款账面值合计（元）", first.loansOnbookTotal],
    ["资产合计（元）", firstTotals.assets],
    ["负债合计（元）", firstTotals.liabilities],
    ["权益合计（元）", firstTotals.equity],
    ["其中未结转累计损益（元）", firstTotals.pnl],
    ["A = L + E", firstTotals.balanced ? "平衡" : "不平衡"],
    ["口径完整性", firstTotals.complete ? "本切片口径内完整" : "不完整（存在未覆盖项或不平衡）"]
  ] : []);
  setMessage("bank-totals-note", available && first
    ? (firstTotals.complete
      ? "合计口径来自既有权威银行报表（含准备金现金、贷款资产、减值准备与未结转损益）；这只是 EXP 实验账，不是监管报表。"
      : "合计不完整，不能当作完整资产负债表："
        + ((firstTotals.unsupported || []).join("；") || "报表不平衡"))
    : "");
  show("bank-summary-empty", available && banks.length === 0);
  fillTable("bank-summary", available ? banks : [], (tr, row) => {
    const totals = row.totals || {};
    cell(tr, row.bankKey);
    cell(tr, row.reserve, "num");
    cell(tr, row.depositsTotal, "num");
    cell(tr, totals.assets, "num");
    cell(tr, totals.liabilities, "num");
    cell(tr, totals.equity, "num");
    cell(tr, totals.contra, "num");
    cell(tr, totals.pnl, "num");
    cell(tr, totals.balanced ? "平衡" : "不平衡");
  });

  const deposits = [];
  const loans = [];
  const gates = [];
  const metrics = [];
  const missing = [];
  if (available) {
    for (const bank of banks) {
      for (const row of bank.customerDeposits || []) {
        deposits.push({ bankKey: bank.bankKey, ...row });
      }
      for (const row of bank.loans || []) {
        loans.push({ bankKey: bank.bankKey, ...row });
      }
      if (bank.gate) {
        gates.push({ bankKey: bank.bankKey, ...bank.gate });
        for (const metric of bank.gate.metrics || []) {
          metrics.push({ bankKey: bank.bankKey, ...metric });
        }
        for (const item of bank.gate.missing || []) {
          missing.push(bank.bankKey + " / " + item);
        }
      }
    }
  }
  show("bank-deposits-empty", available && deposits.length === 0);
  fillTable("bank-deposits", deposits, (tr, row) => {
    cell(tr, row.bankKey);
    cell(tr, row.partyKey);
    cell(tr, row.ownerId);
    cell(tr, row.depositGl);
    cell(tr, row.amount, "num");
    cell(tr, row.nonAccrual ? "是" : "否");
    cell(tr, row.interestPayable, "num");
    cell(tr, row.interestPaid, "num");
  });

  show("bank-loans-empty", available && loans.length === 0);
  fillTable("bank-loans", loans, (tr, row) => {
    const terms = row.terms || {};
    cell(tr, row.borrowerKey);
    cell(tr, row.statusLabel || row.status);
    cell(tr, terms.amount, "num");
    cell(tr, row.onbook, "num");
    cell(tr, row.rateText || row.terms.rateU);
    cell(tr, row.tenorText || row.terms.tenorMs);
  });
  fillTable("bank-loans-raw", loans, (tr, row) => {
    const terms = row.terms || {};
    cell(tr, row.loanId);
    cell(tr, row.bankKey);
    cell(tr, row.borrowerKey);
    cell(tr, row.status);
    cell(tr, row.trancheId || "—");
    cell(tr, terms.amount, "num");
    cell(tr, terms.rateU);
    cell(tr, terms.tenorMs);
    cell(tr, terms.purpose);
    cell(tr, terms.accepted ? "是" : "否");
    cell(tr, terms.approvedBy || "—");
    cell(tr, row.writtenOff, "num");
    cell(tr, row.interestReceivable, "num");
    cell(tr, row.interestPaid, "num");
    cell(tr, row.accruedInterest, "num");
    cell(tr, row.overdue ? "是" : "否");
  });

  show("bank-equity-empty", available && banks.length === 0);
  fillTable("bank-equity", available ? banks : [], (tr, row) => {
    const interest = row.interest || {};
    const equity = row.equity || {};
    cell(tr, row.bankKey);
    cell(tr, interest.interestReceivable, "num");
    cell(tr, interest.interestPayable, "num");
    cell(tr, interest.interestIncome, "num");
    cell(tr, interest.interestExpense, "num");
    cell(tr, equity.paidIn, "num");
    cell(tr, equity.retained, "num");
    cell(tr, equity.nonDistributable, "num");
    cell(tr, equity.unbookedPnl, "num");
    cell(tr, equity.bookEquity, "num");
    cell(tr, equity.totalEquity, "num");
  });
  const glRows = [];
  if (available) {
    for (const bank of banks) {
      for (const [account, amount] of Object.entries(bank.glRaw || {})) {
        glRows.push({ bankKey: bank.bankKey, account, amount });
      }
    }
  }
  fillTable("bank-glraw", glRows, (tr, row) => {
    cell(tr, row.bankKey + " / " + row.account);
    cell(tr, row.amount, "num");
  });

  show("bank-gates-empty", available && gates.length === 0);
  fillTable("bank-gates", gates, (tr, row) => {
    cell(tr, row.bankKey);
    cell(tr, row.status);
    cell(tr, (row.actions || []).join("、") || "无触发动作");
  });
  fillTable("bank-metrics", metrics, (tr, row) => {
    cell(tr, row.bankKey);
    cell(tr, row.name);
    cell(tr, row.num, "num");
    cell(tr, row.den, "num");
    cell(tr, row.value === null || row.value === undefined ? "缺项" : row.value, "num");
    cell(tr, row.state);
  });
  setMessage("bank-missing", missing.length === 0 ? ""
    : "缺项（不补 0、不默认通过）：" + missing.join("；"));

  const custody = (view && view.custody) || {};
  setMessage("bank-custody-note", custody.enabled ? (custody.note || "") :
    (custody.reason || "本档没有托管人。"));
  const pools = custody.pools || [];
  const beneficial = custody.beneficial || [];
  show("bank-custody-pools-empty", !custody.enabled || pools.length === 0);
  show("bank-custody-beneficial-empty", !custody.enabled || beneficial.length === 0);
  fillTable("bank-custody-pools", custody.enabled ? pools : [], (tr, row) => {
    cell(tr, row.custodian);
    cell(tr, row.poolAsset, "num");
  });
  fillTable("bank-custody-beneficial", custody.enabled ? beneficial : [], (tr, row) => {
    cell(tr, row.custodian);
    cell(tr, row.ownerKey);
    cell(tr, row.amount, "num");
  });

  const actions = (view && view.actions) || [];
  show("bank-actions-empty", available && actions.length === 0);
  fillTable("bank-actions", actions, (tr, row) => {
    cell(tr, row.taskId);
    cell(tr, row.personPartyId);
    cell(tr, row.kindLabel || row.kind);
    cell(tr, row.stageLabel || row.stage);
    cell(tr, frameResult(row));
    cell(tr, row.reason);
    cell(tr, row.stage || "");
    cell(tr, row.receipt);
    cell(tr, row.atMillis);
  });
}

/** 单帧结果：accepted 只表示该帧成立，STARTED 不等于完成，未映射阶段不推断完成。 */
function frameResult(row) {
  if (!row || !row.accepted) {
    return "不成立";
  }
  if (row.stage === "STARTED") {
    return "已开始（未完成）";
  }
  if (row.stage === "UNMAPPED") {
    return "该帧成立（阶段未映射）";
  }
  return "该帧成立";
}

/** 人物银行卡片：只呈现服务端派生的本人数据，失败时给出可见提示。 */
async function loadPersonalBank(generation) {
  let result;
  try {
    result = await request("GET", "/api/me/bank");
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "person") {
      setMessage("person-bank-status", "读取本人银行视图失败：" + failure.message);
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== "person") return;
  if (result.status === 401) {
    sessionLost();
    return;
  }
  if (result.status !== 200 || !result.payload) {
    setMessage("person-bank-status", "读取本人银行视图失败（" + result.status + "）。");
    return;
  }
  const view = result.payload;
  setMessage("person-bank-status", view.available === true
    ? "本人 · 世界时点 " + text(view.timeMillis) + " 毫秒"
    : "此档未启用银行模块：" + text(view.reason));
  const owners = $("person-bank-owners");
  owners.replaceChildren();
  for (const row of view.owners || []) {
    const pairs = [
      ["财产主体", row.ownerId],
      ["现金（元）", row.cash],
      ["银行存款（元）", row.deposits],
      ["托管受益权（元）", row.beneficial]
    ];
    for (const [label, value] of pairs) {
      const dt = document.createElement("dt");
      dt.textContent = label;
      const dd = document.createElement("dd");
      dd.textContent = text(value);
      owners.append(dt, dd);
    }
  }
  const deposits = view.deposits || [];
  show("person-bank-deposits-empty", deposits.length === 0);
  fillTable("person-bank-deposits", deposits, (tr, row) => {
    cell(tr, row.bankKey);
    cell(tr, row.partyKey);
    cell(tr, row.amount, "num");
    cell(tr, row.offerId || "—");
    cell(tr, row.rateText || row.rateU);
    cell(tr, row.nonAccrual ? "是" : "否");
  });
  const actions = view.actions || [];
  show("person-bank-actions-empty", actions.length === 0);
  fillTable("person-bank-actions", actions, (tr, row) => {
    cell(tr, row.taskId);
    cell(tr, row.kindLabel || row.kind);
    cell(tr, row.stageLabel || row.stage);
    cell(tr, frameResult(row));
    cell(tr, row.reason);
    cell(tr, row.atMillis);
  });
  const tools = (view.tools || []).join("、") || "无";
  setMessage("person-bank-note", "本人可用工具：" + tools + "。" + text(view.actionsReason));
  renderPersonalBankOps(view);
}

/** 本人银行操作面板：只列真实可用动作与本人可见对象。 */
const BANK_KIND_LABELS = {
  DEPOSIT: "存入", WITHDRAW: "取出", PAY: "支付",
  LOAN_REQUEST: "贷款申请", LOAN_ACCEPT: "接受冻结条款", LOAN_APPROVE: "批准本行合同",
  LOAN_DRAW: "提款放款", LOAN_REPAY: "还本", LOAN_PAY_INTEREST: "付息"
};

function renderPersonalBankOps(view) {
  state.personalBank = view;
  const kinds = (view && view.entitlements) || [];
  show("person-bank-ops", Boolean(view && view.available) && kinds.length > 0);
  const kindSel = $("person-bank-kind");
  const previous = kindSel.value;
  kindSel.replaceChildren();
  for (const kind of kinds) {
    const option = document.createElement("option");
    option.value = kind;
    option.textContent = BANK_KIND_LABELS[kind] || kind;
    kindSel.appendChild(option);
  }
  if (previous && kinds.includes(previous)) kindSel.value = previous;
  const fill = (id, rows) => {
    const select = $(id);
    select.replaceChildren();
    for (const row of rows || []) {
      const option = document.createElement("option");
      option.value = row.token;
      option.textContent = row.label;
      select.appendChild(option);
    }
  };
  const options = (view && view.options) || {};
  fill("person-bank-subject", options.subjects || [{ token: "", label: "本人" }]);
  $("person-bank-subject").value = ((options.subjects || [])[0] || {}).token || "";
  fill("person-bank-source", (options.cash || []).concat(options.deposits || []));
  fill("person-bank-payee", options.payees || []);
  const loanSel = $("person-bank-loan");
  loanSel.replaceChildren();
  for (const loan of (view && view.loans) || []) {
    const option = document.createElement("option");
    option.value = loan.loanId;
    option.textContent = loan.loanId + " · " + (loan.statusLabel || loan.status)
      + " · " + loan.amount + " 元";
    loanSel.appendChild(option);
  }
  show("person-bank-loans-empty", ((view && view.loans) || []).length === 0);
  fillTable("person-bank-loans", (view && view.loans) || [], (tr, row) => {
    cell(tr, row.loanId);
    cell(tr, row.statusLabel || row.status);
    cell(tr, row.amount, "num");
    cell(tr, row.rateText);
    cell(tr, row.tenorText);
    cell(tr, row.purpose);
    cell(tr, row.drawn, "num");
    cell(tr, row.approvedBy || "—");
  });
  togglePersonalBankRows();
}

/** 按动作显示/隐藏字段：资金类要账户，生命周期要合同，申请要条款。 */
function togglePersonalBankRows() {
  const kind = $("person-bank-kind").value;
  const view = state.personalBank || {};
  const options = view.options || {};
  const party = $("person-bank-subject").value || view.personPartyId;
  const sources = kind === "DEPOSIT" ? (options.cash || [])
    : kind === "WITHDRAW" ? (options.deposits || [])
    : (options.cash || []).concat(options.deposits || []);
  const source = $("person-bank-source");
  const selected = source.value;
  const allowed = sources.filter(row => !row.partyKey || row.partyKey === party);
  source.replaceChildren();
  for (const row of allowed) {
    const option = document.createElement("option");
    option.value = row.token;
    option.textContent = row.label;
    source.appendChild(option);
  }
  source.value = allowed.some(row => row.token === selected) ? selected : (allowed[0] || {}).token || "";
  const funds = kind === "DEPOSIT" || kind === "WITHDRAW" || kind === "PAY";
  const lifecycle = kind === "LOAN_ACCEPT" || kind === "LOAN_APPROVE" || kind === "LOAN_DRAW"
    || kind === "LOAN_REPAY" || kind === "LOAN_PAY_INTEREST";
  show("person-bank-source-row", funds);
  show("person-bank-payee-row", kind === "PAY");
  show("person-bank-amount-row", funds || lifecycle || kind === "LOAN_REQUEST");
  show("person-bank-loan-row", lifecycle);
  show("person-bank-terms-row", kind === "LOAN_REQUEST");
}

/** 利率百分数文本 → 定标 1e6 整数（整数运算，不经浮点）。 */
function rateUnits(text) {
  const value = String(text || "").trim();
  if (!/^\d+(\.\d{1,4})?$/.test(value)) {
    throw new Error("利率必须是非负百分数，最多四位小数");
  }
  const parts = value.split(".");
  const whole = Number(parts[0]);
  const frac = ((parts[1] || "") + "0000").slice(0, 4);
  return whole * 10000 + Number(frac);
}

/** 期限天数文本 → 毫秒（1–9999 天）。 */
function tenorMillis(text) {
  const value = String(text || "").trim();
  if (!/^\d{1,4}$/.test(value) || Number(value) <= 0) {
    throw new Error("期限必须是 1 到 9999 天");
  }
  return Number(value) * 86400000;
}

async function submitPersonalBankAction() {
  const generation = state.generation;
  setMessage("person-bank-error", "");
  setMessage("person-bank-result", "");
  let body;
  try {
    const kind = $("person-bank-kind").value;
    const work = Number($("person-bank-work").value);
    const commandId = "web-bank-" + state.generation + "-" + Date.now().toString(36);
    body = { commandId: commandId, taskId: commandId + "-t", kind: kind,
      workMillis: work, amount: $("person-bank-amount").value.trim() };
    if ($("person-bank-subject").value) {
      body.organizationPartyId = $("person-bank-subject").value;
    }
    if (kind === "DEPOSIT" || kind === "WITHDRAW" || kind === "PAY") {
      body.sourceToken = $("person-bank-source").value;
    }
    if (kind === "PAY") {
      body.payeeToken = $("person-bank-payee").value;
    }
    if (kind === "LOAN_REQUEST") {
      body.rateU = rateUnits($("person-bank-rate").value);
      body.tenorMs = tenorMillis($("person-bank-tenor").value);
      body.purpose = $("person-bank-purpose").value.trim();
    }
    if (kind === "LOAN_ACCEPT" || kind === "LOAN_APPROVE" || kind === "LOAN_DRAW"
        || kind === "LOAN_REPAY" || kind === "LOAN_PAY_INTEREST") {
      body.loanId = $("person-bank-loan").value;
    }
  } catch (failure) {
    setMessage("person-bank-error", failure.message);
    return;
  }
  $("person-bank-submit").disabled = true;
  let result;
  try {
    result = await request("POST", "/api/me/bank/action", body);
  } catch (failure) {
    $("person-bank-submit").disabled = false;
    setMessage("person-bank-error",
      "提交结果未知（未收到响应）：" + failure.message + "。请勿盲目重发；先刷新核对本人动作记录与账目。");
    return;
  }
  $("person-bank-submit").disabled = false;
  if (!isCurrent(generation) || state.mode !== "person") return;
  if (result.status === 401 || result.status === 403) {
    sessionLost();
    return;
  }
  if (result.status === 200 && result.payload) {
    setMessage("person-bank-result",
      "已排程：" + text(result.payload.receipt) + " · 到期世界时点 "
      + text(result.payload.dueTimeMillis) + " 毫秒。到期前不会瞬时完成，请稍后刷新或推进世界核对。");
    return;
  }
  const message = (result.payload && result.payload.message) ? result.payload.message
    : ("提交被拒绝（" + result.status + "）");
  setMessage("person-bank-error", message);
}

/** 切换身份/离开人物视角时清空本人银行卡片，避免旧身份的存款留在页面上。 */
function clearPersonalBank() {
  state.personalBank = null;
  if (!$("person-bank-owners")) return;
  $("person-bank-owners").replaceChildren();
  for (const id of ["person-bank-status", "person-bank-note"]) {
    if ($(id)) {
      $(id).textContent = "";
      $(id).hidden = true;
    }
  }
  for (const id of ["person-bank-deposits", "person-bank-actions", "person-bank-loans"]) {
    if ($(id)) fillTable(id, [], () => undefined);
  }
  for (const id of ["person-bank-deposits-empty", "person-bank-actions-empty",
    "person-bank-loans-empty"]) {
    if ($(id)) $(id).hidden = true;
  }
  show("person-bank-ops", false);
  for (const id of ["person-bank-kind", "person-bank-subject", "person-bank-source", "person-bank-payee",
    "person-bank-loan"]) {
    if ($(id)) $(id).replaceChildren();
  }
  for (const id of ["person-bank-amount", "person-bank-rate", "person-bank-tenor",
    "person-bank-purpose"]) {
    if ($(id)) $(id).value = "";
  }
  for (const id of ["person-bank-result", "person-bank-error"]) {
    if ($(id)) {
      $(id).textContent = "";
      $(id).hidden = true;
    }
  }
  if ($("person-bank-submit")) $("person-bank-submit").disabled = false;
}

function fillSection(emptyId, countId, section, note) {
  const total = section ? section.total : 0;
  const shown = section && section.rows ? section.rows.length : 0;
  if ($(emptyId)) $(emptyId).hidden = total !== 0;
  if (countId && $(countId)) {
    $(countId).textContent = total === 0 ? "" : (note || "显示 " + shown + " / 共 " + total + " 条");
  }
}

function fillDl(id, pairs) {
  const dl = $(id);
  dl.replaceChildren();
  for (const [key, value] of pairs) {
    const dt = document.createElement("dt");
    dt.textContent = key;
    const dd = document.createElement("dd");
    dd.textContent = text(value);
    dl.append(dt, dd);
  }
}

function fillNotes(id, notes) {
  const list = $(id);
  list.replaceChildren();
  for (const note of notes || []) {
    const li = document.createElement("li");
    li.textContent = note;
    list.appendChild(li);
  }
}

function fillRules(id, rules) {
  fillDl(id, (rules || []).map(rule => [rule.label, rule.value]));
}

function cutText(view, extra) {
  return "同一确认记录 " + view.position + " · 世界时点 " + view.timeMillis + " 毫秒" + (extra ? " · " + extra : "");
}

function renderCompany(view) {
  $("company-cut").textContent = cutText(view, view.manufacturingAvailable
    ? (view.companyName || view.companyPartyId || "公司") + " · " + text(view.symbol)
    : "本运行未启用制造经营");
  const reportLabels = [
    ["公司经营现金", "companyCash"], ["原材料数量", "rawQuantity"], ["原材料账面金额", "rawAmount"],
    ["原材料单位成本", "rawUnitCost"], ["在制数量", "wipQuantity"], ["在制金额", "wipAmount"],
    ["产成品数量", "finishedQuantity"], ["产成品金额", "finishedAmount"], ["产成品单位成本", "finishedUnitCost"],
    ["应收未收", "receivableOutstanding"], ["设备净值", "equipmentNet"], ["应付未付", "payableOutstanding"],
    ["资产合计", "totalAssets"], ["负债合计", "totalLiabilities"], ["初始权益", "initialEquity"],
    ["累计收入", "revenue"], ["累计销售成本", "costOfGoodsSold"], ["累计期间费用", "periodExpense"],
    ["累计折旧", "depreciation"], ["累计利润", "cumulativeProfit"], ["期末权益", "equity"],
    ["世界现金合计", "worldCash"], ["账平衡校验", "balanced"]
  ];
  const report = view.operatingReport || {};
  fillTable("company-report", reportLabels, (tr, row) => {
    cell(tr, row[0]);
    const value = report[row[1]];
    if (row[1] === "balanced") cell(tr, value === true ? "通过" : value === false ? "不通过" : null);
    else cell(tr, value, "num");
  });
  const noteNode = $("company-report-note");
  if (view.reportError) {
    noteNode.textContent = "报表未能派生：" + view.reportError;
  } else if (!view.manufacturingAvailable) {
    noteNode.textContent = "此运行未启用制造经营，经营数据不可用，界面不填 0。";
  } else {
    noteNode.textContent = "金额与数量取自同一确认快照；账平衡校验按资产 = 负债 + 权益与世界现金守恒判定。";
  }

  fillTable("company-inventory", (view.inventories || {}).rows, (tr, row) => {
    cell(tr, row.label);
    cell(tr, row.quantity, "num");
    cell(tr, row.amount, "num");
    cell(tr, row.unitCost, "num");
  });
  fillSection("company-inventory-empty", null, view.inventories);

  fillTable("company-orders", (view.purchaseOrders || {}).rows, (tr, row) => {
    cell(tr, row.purchaseOrderId);
    cell(tr, row.quantity, "num");
    cell(tr, row.unitPrice, "num");
    cell(tr, row.orderedAt, "num");
    cell(tr, row.arrivalAt, "num");
    cell(tr, label("order", row.status));
  });
  fillSection("company-orders-empty", "company-orders-count", view.purchaseOrders);

  fillTable("company-batches", (view.productionBatches || {}).rows, (tr, row) => {
    cell(tr, row.batchId);
    cell(tr, row.quantity, "num");
    cell(tr, row.startedAt, "num");
    cell(tr, row.completesAt, "num");
    cell(tr, row.materialCost, "num");
    cell(tr, row.laborCost, "num");
    cell(tr, label("batch", row.status));
  });
  fillSection("company-batches-empty", "company-batches-count", view.productionBatches);

  fillTable("company-contracts", (view.contracts || {}).rows, (tr, row) => {
    cell(tr, row.contractId);
    cell(tr, row.quantity, "num");
    cell(tr, row.unitPrice, "num");
    cell(tr, row.signedAt, "num");
    cell(tr, row.promisedDeliveryAt, "num");
    cell(tr, row.period, "num");
    cell(tr, row.deliveredQuantity, "num");
    cell(tr, row.remainingQuantity, "num");
  });
  fillSection("company-contracts-empty", "company-contracts-count", view.contracts);

  fillTable("company-receivables", (view.receivables || {}).rows, (tr, row) => {
    cell(tr, row.receivableId);
    cell(tr, row.contractId);
    cell(tr, row.quantity, "num");
    cell(tr, row.amount, "num");
    cell(tr, row.cost, "num");
    cell(tr, row.outstanding, "num");
    cell(tr, row.dueAt, "num");
    cell(tr, label("receivable", row.status));
  });
  fillSection("company-receivables-empty", "company-receivables-count", view.receivables);

  fillTable("company-bills", (view.expenseBills || {}).rows, (tr, row) => {
    cell(tr, row.billId);
    cell(tr, row.period, "num");
    cell(tr, row.amount, "num");
    cell(tr, row.paid, "num");
    cell(tr, row.closedAt, "num");
  });
  fillSection("company-bills-empty", "company-bills-count", view.expenseBills);

  const charter = view.charter || {};
  fillDl("company-charter", [
    ["公司组织", charter.companyPartyId],
    ["公司名称", charter.companyName],
    ["主席席位", charter.chairSeatId],
    ["法定人数", charter.quorum]
  ]);
  fillTable("company-seats", charter.seats, (tr, row) => {
    cell(tr, row.seatId);
    cell(tr, row.partyId);
    cell(tr, row.partyName);
    cell(tr, row.chair ? "主席" : "成员");
  });
  if ($("company-seats-empty")) $("company-seats-empty").hidden = Boolean(view.governanceAvailable);

  fillTable("company-meetings", (view.meetings || {}).rows, (tr, row) => {
    cell(tr, row.meetingId);
    cell(tr, label("topic", row.topic));
    cell(tr, row.convenerSeatId);
    cell(tr, label("meeting", row.status));
    cell(tr, row.materialCount, "num");
    cell(tr, row.attendees);
    cell(tr, row.recused);
    cell(tr, (row.ballots || "").split(";").filter(Boolean)
      .map(item => { const [seat, vote] = item.split("="); return seat + "=" + label("vote", vote); })
      .join("；"));
    cell(tr, row.proposedLines);
    cell(tr, row.failureReason || row.dividendSchemeId);
  });
  fillSection("company-meetings-empty", "company-meetings-count", view.meetings);

  fillTable("company-budgets", (view.budgets || {}).rows, (tr, row) => {
    cell(tr, row.period, "num");
    cell(tr, label("category", row.category));
    cell(tr, row.cap, "num");
  });
  fillSection("company-budgets-empty", null, view.budgets);

  fillTable("company-commitments", (view.commitments || {}).rows, (tr, row) => {
    cell(tr, row.commitmentId);
    cell(tr, row.period, "num");
    cell(tr, label("category", row.category));
    cell(tr, row.reserved, "num");
    cell(tr, row.spent, "num");
    cell(tr, row.released, "num");
    cell(tr, row.outstanding, "num");
    cell(tr, label("commitment", row.status));
  });
  fillSection("company-commitments-empty", null, view.commitments);

  fillTable("company-reviews", (view.reviews || {}).rows, (tr, row) => {
    cell(tr, row.reviewId);
    cell(tr, label("review", row.status));
    cell(tr, row.total, "num");
    cell(tr, (row.missingDimensions || "").split(",").filter(Boolean)
      .map(item => label("dimension", item)).join("、") || "无");
    cell(tr, (row.metrics || "").split(";").filter(Boolean)
      .map(item => { const [key, value] = item.split("="); return label("dimension", key) + " " + value; })
      .join("；") || "无");
  });
  fillSection("company-reviews-empty", null, view.reviews);

  fillTable("company-evidence", (view.periodEvidence || {}).rows, (tr, row) => {
    cell(tr, row.period, "num");
    cell(tr, row.capturedAt, "num");
    cell(tr, row.position, "num");
    cell(tr, row.operatingCommitment, "num");
    cell(tr, row.cashSolvency, "num");
    cell(tr, row.risk, "num");
    cell(tr, row.contractIds);
    cell(tr, row.receivableIds);
  });
  fillSection("company-evidence-empty", null, view.periodEvidence);

  renderCompanyReports();
  fillRules("company-rules", view.rules);
  fillNotes("company-notes", view.notes);
}

function renderCompanyReports() {
  const view = state.details.company;
  if (!view) return;
  const section = view.managementReports || { total: 0, rows: [] };
  const mode = $("company-report-filter").value;
  const rows = (section.rows || []).filter(row => mode === "ALL"
    || (mode === "ACCEPTED" ? row.accepted === true : row.accepted !== true));
  fillTable("company-reports", rows, (tr, row) => {
    cell(tr, row.source === "GOVERNANCE" ? "治理" : "制造");
    cell(tr, row.taskId);
    cell(tr, row.kind);
    cell(tr, row.accepted ? "成功" : "拒绝");
    cell(tr, row.reason);
    cell(tr, row.subjectId);
    cell(tr, row.amount, "num");
    cell(tr, row.timeMillis, "num");
  });
  $("company-reports-empty").hidden = rows.length !== 0;
  $("company-reports-count").textContent = "显示 " + rows.length + " / 本页 " + (section.rows || []).length
    + " 条（总计 " + section.total + " 条）";
}

function renderPerformance(view) {
  $("performance-cut").textContent = cutText(view, view.performanceAvailable
    ? "期间表现按 PerformanceModel 计算" : "确认帧过长，本期未计算期间表现");
  fillTable("performance-accounts", (view.accounts || {}).rows, (tr, row) => {
    cell(tr, row.accountId);
    cell(tr, row.ownerId);
    cell(tr, row.ownerName);
    cell(tr, row.cash, "num");
    cell(tr, row.source);
  });
  fillSection("performance-accounts-empty", null, view.accounts);
  renderPerformanceOwners();
  fillTable("performance-holdings", (view.holdings || {}).rows, (tr, row) => {
    cell(tr, row.ownerId);
    cell(tr, row.accountId);
    cell(tr, row.symbol);
    cell(tr, row.quantity, "num");
    cell(tr, label("priceSource", row.priceSource));
    cell(tr, row.price, "num");
    cell(tr, row.value, "num");
  });
  fillSection("performance-holdings-empty", "performance-holdings-count", view.holdings);
  fillTable("performance-flows", (view.flows || {}).rows, (tr, row) => {
    cell(tr, row.flowId);
    cell(tr, row.position, "num");
    cell(tr, row.atMillis, "num");
    cell(tr, row.fromOwnerId + " / " + row.fromAccountId);
    cell(tr, row.toOwnerId + " / " + row.toAccountId);
    cell(tr, row.amount, "num");
    cell(tr, row.evidenceRef);
  });
  fillSection("performance-flows-empty", "performance-flows-count", view.flows);
  fillNotes("performance-notes", view.notes);
  fillPerformanceHistoryOwners(view);
  fillPerformanceHistoryRange(view.position);
  if (state.page === "performance" && state.mode === "admin") {
    guard(loadPerformanceHistory());
  }
}

function renderPerformanceOwners() {
  const view = state.details.performance;
  if (!view) return;
  const section = view.owners || { total: 0, rows: [] };
  const mode = $("performance-filter").value;
  const rows = (section.rows || []).filter(row => mode === "ALL" || row.valuationStatus === mode);
  fillTable("performance-owners", rows, (tr, row) => {
    cell(tr, row.ownerId);
    cell(tr, label("ownerKind", row.kind));
    cell(tr, label("valuation", row.valuationStatus));
    cell(tr, row.netWorth, "num");
    cell(tr, row.cash, "num");
    cell(tr, row.holdings, "num");
    cell(tr, row.dividendReceivable, "num");
    cell(tr, row.dividendPayable, "num");
    cell(tr, label("performance", row.performanceStatus) + (row.performanceNote ? "（" + row.performanceNote + "）" : ""));
    cell(tr, row.returnRate, "num");
    cell(tr, row.index, "num");
    cell(tr, row.maxDrawdown, "num");
    cell(tr, row.capitalFlowCount, "num");
  });
  $("performance-owners-empty").hidden = rows.length !== 0;
  $("performance-owners-count").textContent = "显示 " + rows.length + " / 本页 " + (section.rows || []).length
    + " 条（总计 " + section.total + " 条）";
}

// ---------------------------------------------------------------- 规模/指数/回撤历史

const PERFORMANCE_CHART = { left: 76, right: 936, top: 16, bottom: 158 };
const PERFORMANCE_FLOW_LABEL = { CAPITAL_CONTRIBUTION: "流入", CAPITAL_WITHDRAWAL: "流出" };

function clearPerformanceHistoryVisuals() {
  state.performanceHistory = null;
  for (const id of ["performance-scale-chart", "performance-index-chart", "performance-drawdown-chart"]) {
    const svg = $(id);
    if (svg) { svg.replaceChildren(); svg.setAttribute("hidden", ""); }
  }
  for (const id of ["performance-points", "performance-history-flows"]) {
    if ($(id)) fillTable(id, [], () => undefined);
  }
  for (const id of ["performance-history-cut", "performance-history-count", "performance-history-range",
    "performance-points-count", "performance-history-flows-count"]) {
    if ($(id)) $(id).textContent = "";
  }
  if ($("performance-chart-caption")) $("performance-chart-caption").textContent = "尚未查询历史区间。";
  if ($("performance-points-empty")) $("performance-points-empty").hidden = true;
  if ($("performance-history-flows-empty")) $("performance-history-flows-empty").hidden = true;
}

function clearPerformanceHistoryDom() {
  state.performanceRequest += 1;
  clearPerformanceHistoryVisuals();
  if ($("performance-history-status")) $("performance-history-status").textContent = "选择主体与起止确认位置后查询。";
  setMessage("performance-history-error", "");
}

/** 主体下拉只来自本页已读取的净资产行；不额外请求、不猜主体。 */
function fillPerformanceHistoryOwners(view) {
  const select = $("performance-history-owner");
  if (!select) return;
  const previous = select.value;
  const rows = (view && view.owners ? view.owners.rows : []) || [];
  select.replaceChildren();
  for (const row of rows) {
    const option = document.createElement("option");
    option.value = row.ownerId;
    option.textContent = row.ownerName && row.ownerName !== row.ownerId
      ? row.ownerId + "（" + row.ownerName + "）" : row.ownerId;
    select.appendChild(option);
  }
  if (!rows.length) {
    const option = document.createElement("option");
    option.value = "";
    option.textContent = "（没有可估值主体）";
    select.appendChild(option);
  }
  const owners = rows.map(row => row.ownerId);
  select.value = owners.includes(previous) ? previous : (owners[0] || "");
}

/** 默认填入“最近至多 400 帧”的请求值；响应里始终写实际起点。 */
function fillPerformanceHistoryRange(position) {
  if (!position) return;
  const end = BigInt(position);
  const start = end - 399n > 1n ? end - 399n : 1n;
  if (!$("performance-history-end").value) $("performance-history-end").value = end.toString();
  if (!$("performance-history-start").value) $("performance-history-start").value = start.toString();
}

function performancePositionText(value) {
  return /^(0|[1-9][0-9]*)$/.test(String(value || "").trim()) ? String(value).trim() : null;
}

async function loadPerformanceHistory(generation = state.generation,
                                      pageVersion = state.pageVersion) {
  if (state.mode !== "admin" || state.page !== "performance") return;
  const ownerId = $("performance-history-owner").value || "";
  const startText = performancePositionText($("performance-history-start").value);
  const endText = performancePositionText($("performance-history-end").value);
  const requestId = ++state.performanceRequest;
  setMessage("performance-history-error", "");
  if (!ownerId) {
    $("performance-history-status").textContent = "没有可查询的财产主体。";
    return;
  }
  if (!startText || !endText) {
    $("performance-history-status").textContent = "";
    setMessage("performance-history-error", "起止位置必须是十进制整数（不接受小数、负号、空格或科学计数法）。");
    return;
  }
  $("performance-history-status").textContent = "正在读取所选区间的确认历史…";
  let result;
  try {
    result = await request("POST", "/api/admin/performance-history",
      { ownerId, startPosition: startText, endPosition: endText });
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "admin" && pageVersion === state.pageVersion
        && requestId === state.performanceRequest) {
      $("performance-history-status").textContent = "";
      setMessage("performance-history-error", failure.message);
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin" || pageVersion !== state.pageVersion
      || state.page !== "performance" || requestId !== state.performanceRequest
      || ($("performance-history-owner").value || "") !== ownerId
      || performancePositionText($("performance-history-start").value) !== startText
      || performancePositionText($("performance-history-end").value) !== endText) return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) {
    clearPerformanceHistoryVisuals();
    $("performance-history-status").textContent = "";
    const message = result.payload && result.payload.message
      ? result.payload.message : "读取表现历史失败（" + result.status + "）";
    setMessage("performance-history-error", message);
    return;
  }
  renderPerformanceHistory(result.payload);
}

function renderPerformanceHistory(view) {
  state.performanceHistory = view;
  $("performance-history-cut").textContent = "运行 " + view.runId + " · 实时截止 记录 "
    + view.position + " · " + view.timeMillis + " 毫秒";
  const laterThanEnd = BigInt(view.endPosition) < BigInt(view.position);
  $("performance-history-range").textContent = "所选区间 " + view.startPosition + "（"
    + view.startTimeMillis + " 毫秒）– " + view.endPosition + "（" + view.endTimeMillis
    + " 毫秒）· 共 " + view.totalPoints + " 个确认点 · 单次上限 " + view.framesLimit + " 帧"
    + (laterThanEnd ? " · 终点早于实时截止，图只覆盖所选区间" : "");
  $("performance-history-count").textContent = view.totalPoints + " 点";
  const period = view.performance || {};
  const statusText = ({ COMPLETE: "完整可用", INCOMPLETE_VALUATION: "存在缺价或组织不支持：指数与回撤不适用",
    NON_POSITIVE_EQUITY: "存在零或负权益：指数与回撤不适用", NO_PERIOD: "不足两个确认点：无期间" })[period.status]
    || text(period.status);
  $("performance-history-status").textContent = statusText + " · 收益率 " + text(period.returnRate)
    + " · 指数 " + text(period.index) + " · 最大回撤 " + text(period.maxDrawdown)
    + " · 相邻区间 " + text(period.segmentCount) + " · 期间 " + text(period.periodId);

  const points = view.points || [];
  fillTable("performance-points", points, (tr, row) => {
    cell(tr, row.position, "num");
    cell(tr, row.timeMillis, "num");
    cell(tr, label("valuation", row.valuationStatus));
    cell(tr, row.navBeforeFlow, "num");
    cell(tr, row.navAfterFlow, "num");
    cell(tr, row.index, "num");
    cell(tr, row.drawdown, "num");
    cell(tr, (row.flows || []).map(flow => (PERFORMANCE_FLOW_LABEL[flow.kind] || flow.kind)
      + " " + flow.amount).join("；"));
  });
  $("performance-points-empty").hidden = points.length !== 0;
  $("performance-points-count").textContent = points.length === 0 ? ""
    : "显示 " + points.length + " 行（每个确认点一行）";

  const flows = (view.flows || { rows: [] });
  fillTable("performance-history-flows", flows.rows, (tr, row) => {
    cell(tr, row.flowId);
    cell(tr, PERFORMANCE_FLOW_LABEL[row.kind] || row.kind);
    cell(tr, row.position, "num");
    cell(tr, row.atMillis, "num");
    cell(tr, row.fromOwnerId + " / " + row.fromAccountId);
    cell(tr, row.toOwnerId + " / " + row.toAccountId);
    cell(tr, row.amount, "num");
    cell(tr, row.evidenceRef);
  });
  $("performance-history-flows-empty").hidden = flows.rows.length !== 0;
  $("performance-history-flows-count").textContent = flows.rows.length === 0 ? ""
    : "显示 " + flows.rows.length + " / 共 " + flows.total + " 条";
  renderPerformanceCharts(view);
}

/** 精确十进制转 BigInt（指定 scale）；不接受指数记数法，解析失败返回 null。 */
function decimalToScaled(value, scale) {
  const raw = String(value === null || value === undefined ? "" : value).trim();
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(raw);
  if (!match) return null;
  const fraction = (match[3] || "").padEnd(scale, "0").slice(0, scale);
  const magnitude = BigInt(match[2]) * (10n ** BigInt(scale)) + BigInt(fraction || "0");
  return match[1] === "-" ? -magnitude : magnitude;
}

function scaledToText(value, scale) {
  const negative = value < 0n;
  const absolute = negative ? -value : value;
  const unit = 10n ** BigInt(scale);
  const integer = absolute / unit;
  const fraction = (absolute % unit).toString().padStart(scale, "0");
  return (negative ? "-" : "") + integer.toString() + (scale > 0 ? "." + fraction : "");
}

function performanceAxis(entries) {
  const first = BigInt(entries[0].point.position);
  const last = BigInt(entries[entries.length - 1].point.position);
  return { first, span: last - first };
}

/** 横轴 = 确认位置线性映射；span 为 0（单点）时居中。axis 只算一次，不在每点重扫。 */
function performanceChartX(axis, position) {
  const left = PERFORMANCE_CHART.left;
  const right = PERFORMANCE_CHART.right;
  if (axis.span === 0n) return (left + right) / 2;
  return left + Number((BigInt(position) - axis.first) * BigInt(right - left) / axis.span);
}

function drawPerformanceSeries(svg, entries, options) {
  const known = entries.filter(entry => entry.value !== null);
  if (!known.length) return false;
  let min = known[0].value;
  let max = known[0].value;
  for (const entry of known) {
    if (entry.value < min) min = entry.value;
    if (entry.value > max) max = entry.value;
  }
  let span = max - min;
  if (span === 0n) {
    const magnitude = max < 0n ? -max : max;
    span = magnitude / 50n;
    if (span === 0n) span = 1n;
    min -= span / 2n;
    max += span / 2n;
  }
  const padding = span / 8n || 1n;
  const low = min - padding;
  const high = max + padding;
  const range = high - low;
  const top = PERFORMANCE_CHART.top;
  const bottom = PERFORMANCE_CHART.bottom;
  const y = (value) => bottom - Number((value - low) * BigInt(bottom - top) / range);
  const scale = options.scale;
  const axis = performanceAxis(entries);

  for (let index = 0; index < 4; index++) {
    const value = low + range * BigInt(index) / 3n;
    const lineY = y(value);
    svg.appendChild(svgNode("line", { x1: PERFORMANCE_CHART.left, y1: lineY,
      x2: PERFORMANCE_CHART.right, y2: lineY, class: "chart-grid" }));
    svg.appendChild(svgNode("text", { x: PERFORMANCE_CHART.left - 10, y: lineY + 4,
      "text-anchor": "end", class: "chart-label" }, scaledToText(value, scale)));
  }
  if (options.baseline !== undefined) {
    const baseY = y(options.baseline);
    svg.appendChild(svgNode("line", { x1: PERFORMANCE_CHART.left, y1: baseY,
      x2: PERFORMANCE_CHART.right, y2: baseY, class: "chart-baseline" }));
    svg.appendChild(svgNode("text", { x: PERFORMANCE_CHART.right, y: baseY - 4,
      "text-anchor": "end", class: "chart-label" }, options.baselineLabel));
  }

  let path = "";
  let open = false;
  for (const entry of entries) {
    if (entry.value === null) { open = false; continue; }
    const px = Math.round(performanceChartX(axis, entry.point.position));
    const py = Math.round(y(entry.value));
    path += (open ? " L " : " M ") + px + " " + py;
    open = true;
  }
  if (path) svg.appendChild(svgNode("path", { d: path.trim(), class: "chart-line " + options.className }));

  if (entries.length <= 240) {
    for (const entry of entries) {
      if (entry.value === null) continue;
      const circle = svgNode("circle", { cx: performanceChartX(axis, entry.point.position),
        cy: y(entry.value), r: 2.5, class: "chart-point" });
      circle.appendChild(svgNode("title", {}, "位置 " + entry.point.position + " · "
        + entry.point.timeMillis + " 毫秒 · " + options.unit + " "
        + scaledToText(entry.value, scale)));
      svg.appendChild(circle);
    }
  }
  return true;
}

function renderPerformanceChart(id, points, key, scale, options = {}) {
  const svg = $(id);
  if (!svg) return false;
  svg.replaceChildren();
  svg.setAttribute("hidden", "");
  if (!points.length) return false;
  const entries = points.map(point => ({ point, value: decimalToScaled(point[key], scale) }));
  const drawn = drawPerformanceSeries(svg, entries, { scale, className: options.className || "",
    baseline: options.baseline, baselineLabel: options.baselineLabel, unit: options.unit });
  if (!drawn) return false;
  if (options.flowMarkers) {
    const axis = performanceAxis(entries);
    for (const point of points) {
      for (const flow of point.flows || []) {
        const x = Math.round(performanceChartX(axis, point.position));
        const line = svgNode("line", { x1: x, y1: PERFORMANCE_CHART.top, x2: x,
          y2: PERFORMANCE_CHART.bottom, class: "chart-flow" });
        line.appendChild(svgNode("title", {}, (PERFORMANCE_FLOW_LABEL[flow.kind] || flow.kind)
          + " " + flow.amount + " · 位置 " + point.position));
        svg.appendChild(line);
      }
    }
  }
  svg.appendChild(svgNode("text", { x: PERFORMANCE_CHART.left, y: 182, class: "chart-label" },
    "位置 " + points[0].position));
  svg.appendChild(svgNode("text", { x: PERFORMANCE_CHART.right, y: 182, "text-anchor": "end",
    class: "chart-label" }, "位置 " + points[points.length - 1].position));
  svg.removeAttribute("hidden");
  return true;
}

function renderPerformanceCharts(view) {
  const points = view.points || [];
  const caption = $("performance-chart-caption");
  if (!points.length) {
    for (const id of ["performance-scale-chart", "performance-index-chart", "performance-drawdown-chart"]) {
      const svg = $(id);
      if (svg) { svg.replaceChildren(); svg.setAttribute("hidden", ""); }
    }
    caption.textContent = "所选区间没有确认点。";
    return;
  }
  const scaleDrawn = renderPerformanceChart("performance-scale-chart", points, "navAfterFlow", 2,
    { unit: "元", flowMarkers: true });
  const indexDrawn = renderPerformanceChart("performance-index-chart", points, "index", 12,
    { unit: "指数", baseline: 10n ** 12n, baselineLabel: "基数 1", className: "chart-index" });
  const drawdownDrawn = renderPerformanceChart("performance-drawdown-chart", points, "drawdown", 12,
    { unit: "回撤", baseline: 0n, baselineLabel: "回撤 0", className: "chart-drawdown" });
  const complete = view.performance && view.performance.status === "COMPLETE";
  caption.textContent = "横轴 = 确认记录位置（" + points[0].position + "–"
    + points[points.length - 1].position + "），不是均匀时间；纵轴单位：资金规模 元 / 指数（基数 1）/ 回撤。"
    + (complete ? "" : " 期间不完整：指数与回撤按口径留空，只画已知的资金规模。")
    + (scaleDrawn ? "" : " 资金规模缺少可用估值。")
    + (indexDrawn ? "" : " 没有可画的表现指数。")
    + (drawdownDrawn ? "" : " 没有可画的回撤。");
}

function renderPeopleAdmin(view) {
  $("org-cut").textContent = cutText(view, "登记表与任职记录");
  fillTable("org-parties", (view.parties || {}).rows, (tr, row) => {
    cell(tr, row.partyId);
    cell(tr, label("partyKind", row.kind));
    cell(tr, row.name);
    cell(tr, row.ownerIds);
  });
  fillSection("org-parties-empty", null, view.parties);
  fillTable("org-owners", (view.owners || {}).rows, (tr, row) => {
    cell(tr, row.ownerId);
    cell(tr, label("ownerKind", row.kind));
    cell(tr, row.partyId);
    cell(tr, row.partyName);
    cell(tr, row.accountIds);
    cell(tr, row.cash, "num");
  });
  fillSection("org-owners-empty", null, view.owners);
  renderAppointments();
  fillTable("org-mandates", (view.mandates || {}).rows, (tr, row) => {
    cell(tr, row.productOwnerId);
    cell(tr, row.productName);
    cell(tr, row.organizationPartyId);
    cell(tr, row.accountIds);
  });
  fillSection("org-mandates-empty", null, view.mandates);
  fillTable("org-reports", (view.managementReports || {}).rows, (tr, row) => {
    cell(tr, row.taskId);
    cell(tr, row.kind);
    cell(tr, row.accepted ? "成功" : "拒绝");
    cell(tr, row.reason);
    cell(tr, row.subjectId);
    cell(tr, row.timeMillis, "num");
  });
  fillSection("org-reports-empty", "org-reports-count", view.managementReports);
  fillNotes("org-notes", view.notes);
}

function renderAppointments() {
  const view = state.details.org;
  if (!view) return;
  const section = view.appointments || { total: 0, rows: [] };
  const mode = $("org-appointment-filter").value;
  const rows = (section.rows || []).filter(row => mode === "ALL" || row.status === mode);
  fillTable("org-appointments", rows, (tr, row) => {
    cell(tr, row.appointmentId);
    cell(tr, row.personPartyId + (row.personName ? "（" + row.personName + "）" : ""));
    cell(tr, row.organizationPartyId + (row.organizationName ? "（" + row.organizationName + "）" : ""));
    cell(tr, label("role", row.role));
    cell(tr, row.startsAt, "num");
    cell(tr, row.endsAt, "num");
    cell(tr, row.effectiveEnd, "num");
    cell(tr, row.revokedAt, "num");
    cell(tr, label("appointment", row.status));
  });
  $("org-appointments-empty").hidden = rows.length !== 0;
  $("org-appointments-count").textContent = "显示 " + rows.length + " / 本页 " + (section.rows || []).length
    + " 条（总计 " + section.total + " 条）";
}

function renderInformation(view) {
  $("information-cut").textContent = cutText(view, "资料版本与实际阅读经历");
  fillTable("information-documents", (view.documents || {}).rows, (tr, row) => {
    cell(tr, row.docId);
    cell(tr, row.title);
    cell(tr, row.publisherPartyId + (row.publisherName ? "（" + row.publisherName + "）" : ""));
    cell(tr, label("audience", row.audienceKind)
      + (row.audiencePartyId ? "：" + row.audiencePartyId : ""));
    cell(tr, row.latestVersion, "num");
    cell(tr, row.versionCount, "num");
    cell(tr, row.publishedAt, "num");
    cell(tr, row.readDurationMillis, "num");
    cell(tr, row.bodyPreview, "preview");
  });
  fillSection("information-documents-empty", "information-documents-count", view.documents);
  fillTable("information-active", (view.activeReadings || {}).rows, (tr, row) => {
    cell(tr, row.readId);
    cell(tr, row.personPartyId + (row.personName ? "（" + row.personName + "）" : ""));
    cell(tr, row.docId);
    cell(tr, row.version, "num");
    cell(tr, row.startedAt, "num");
    cell(tr, row.dueAt, "num");
  });
  fillSection("information-active-empty", "information-active-count", view.activeReadings);
  renderClosedReadings();
  fillTable("information-observations", (view.observations || {}).rows, (tr, row) => {
    cell(tr, row.readId);
    cell(tr, row.personPartyId + (row.personName ? "（" + row.personName + "）" : ""));
    cell(tr, row.docId);
    cell(tr, row.version, "num");
    cell(tr, row.title);
    cell(tr, row.completedAt, "num");
    cell(tr, row.bodyPreview, "preview");
  });
  fillSection("information-observations-empty", "information-observations-count", view.observations);
  fillTable("information-readers", (view.readers || {}).rows, (tr, row) => {
    cell(tr, row.partyId);
    cell(tr, row.name);
    cell(tr, row.activeReadings, "num");
    cell(tr, row.completedReadings, "num");
  });
  fillSection("information-readers-empty", null, view.readers);
  const selectedPerson = $("recall-person").value;
  $("recall-person").replaceChildren();
  for (const reader of (view.readers || {}).rows || []) {
    const option = document.createElement("option");
    option.value = reader.partyId;
    option.textContent = reader.partyId + (reader.name ? "（" + reader.name + "）" : "");
    $("recall-person").appendChild(option);
  }
  if (Array.from($("recall-person").options).some(option => option.value === selectedPerson)) {
    $("recall-person").value = selectedPerson;
  }
  loadRecall();
  loadBeliefs();
  fillTable("information-reports", (view.managementReports || {}).rows, (tr, row) => {
    cell(tr, row.taskId);
    cell(tr, row.kind);
    cell(tr, row.accepted ? "成功" : "拒绝");
    cell(tr, row.reason);
    cell(tr, row.subjectId);
    cell(tr, row.readerPartyId);
    cell(tr, row.timeMillis, "num");
  });
  fillSection("information-reports-empty", "information-reports-count", view.managementReports);
  fillNotes("information-notes", view.notes);
}

function clearRecall() {
  state.recallRequest += 1;
  $("recall-cut").textContent = "";
  $("recall-status").textContent = "";
  $("recall-policy").replaceChildren();
  $("recall-items").replaceChildren();
  setMessage("recall-error", "");
}

async function loadRecall() {
  clearRecall();
  const personId = $("recall-person").value;
  if (!personId || state.mode !== "admin" || state.page !== "information") return;
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  const requestId = state.recallRequest;
  const current = () => isCurrent(generation) && state.mode === "admin"
    && state.page === "information" && state.pageVersion === pageVersion
    && state.recallRequest === requestId && $("recall-person").value === personId;
  $("recall-status").textContent = "正在查询…";
  try {
    const response = await request("POST", "/api/admin/recall", { personId });
    if (!current()) return;
    if (response.status === 401 || response.status === 403) { sessionLost(); return; }
    if (response.status !== 200 || !response.payload) throw new Error("读取回忆失败，请稍后重试。");
    const view = response.payload;
    renderAdminCut(view);
    $("recall-cut").textContent = cutText(view, "人物 " + view.personPartyId);
    if (!view.available) {
      $("recall-status").textContent = "当前档案未启用回忆实验；原有资料与阅读记录保持原口径。";
      return;
    }
    const policy = view.policy;
    fillDl("recall-policy", [["半衰期（模拟毫秒）", policy.halfLifeMillis],
      ["最低分（满分1000）", policy.thresholdPermille], ["最多经历", policy.maxItems],
      ["内容字符预算（含标题及来源，UTF16）", policy.maxContentChars]]);
    $("recall-status").textContent = "达到回忆阈值 " + view.eligibleCount + " 条 · 低于阈值 "
      + view.forgottenCount + " 条 · 当前选中 " + view.selected.length + " 条 · 已选内容字符 "
      + view.contentChars + (view.budgetTruncated ? " · 有内容因条数或字符预算未选入" : " · 未触发预算截断");
    for (const item of view.selected) {
      const detail = document.createElement("details");
      const summary = document.createElement("summary");
      summary.textContent = item.title + " · " + item.docId + " v" + item.version + " · 分数 " + item.score;
      const meta = document.createElement("p");
      meta.className = "hint";
      meta.textContent = "首次读完 " + item.completedAt + " · 最近读完 " + item.lastReadAt
        + " · 阅读记录 " + item.lastReadId + " · 公开来源：" + text(item.publicSourceDescription);
      const body = document.createElement("pre");
      body.className = "recall-body";
      body.textContent = item.body;
      detail.append(summary, meta, body);
      $("recall-items").appendChild(detail);
    }
  } catch (failure) {
    if (current()) {
      $("recall-status").textContent = "";
      setMessage("recall-error", failure.message);
    }
  }
}

$("recall-person").addEventListener("change", () => { loadRecall(); loadBeliefs(); });
$("recall-refresh").addEventListener("click", () => loadRecall());

const BELIEF_LABELS = {
  OPEN: "待核验", HIT: "符合判断", MISS: "判断落空", LOW: "低", MID: "中", HIGH: "高",
  VERIFY_BEFORE_ACT: "行动前核实", SEEK_SECOND_SOURCE: "寻找第二个来源"
};
function beliefLabel(value) { return BELIEF_LABELS[value] || text(value); }

function clearBeliefs() {
  state.beliefRequest += 1;
  for (const id of ["belief-cut", "belief-status", "belief-evaluations-count", "belief-revisions-count"]) $(id).textContent = "";
  for (const id of ["belief-policy", "belief-items", "belief-revisions"]) $(id).replaceChildren();
  for (const id of ["belief-sources", "belief-habits", "belief-evaluations"]) fillTable(id, [], () => undefined);
  setMessage("belief-error", "");
}

async function loadBeliefs() {
  clearBeliefs();
  const personId = $("recall-person").value;
  if (!personId || state.mode !== "admin" || state.page !== "information") return;
  const generation = state.generation;
  const pageVersion = state.pageVersion;
  const requestId = state.beliefRequest;
  const current = () => isCurrent(generation) && state.mode === "admin"
    && state.page === "information" && state.pageVersion === pageVersion
    && state.beliefRequest === requestId && $("recall-person").value === personId;
  $("belief-status").textContent = "正在查询…";
  try {
    const response = await request("POST", "/api/admin/beliefs", { personId });
    if (!current()) return;
    if (response.status === 401 || response.status === 403) { sessionLost(); return; }
    if (response.status !== 200 || !response.payload) throw new Error("读取判断与学习失败，请稍后重试。");
    const view = response.payload;
    renderAdminCut(view);
    $("belief-cut").textContent = cutText(view, "人物 " + view.personPartyId);
    if (!view.available) {
      $("belief-status").textContent = "当前档案未启用判断与学习实验；没有自动改动旧运行。";
      return;
    }
    const policy = view.policy;
    fillDl("belief-policy", [["判断总量上限", policy.maxBeliefsPerPerson], ["待核验上限", policy.maxOpenBeliefs],
      ["最远预测（模拟毫秒）", policy.maxHorizonMillis], ["每次来源分数变化", policy.trustStep],
      ["置信影响权重", policy.confidenceWeight], ["每级习惯支持数", policy.habitThreshold],
      ["习惯支持窗口（模拟毫秒）", policy.habitWindowMillis]]);
    $("belief-status").textContent = "判断 " + view.beliefs.total + " 条 · 本页 " + view.beliefs.rows.length
      + " 条 · 累计核验 " + view.evaluations.total + " 次";
    for (const item of view.beliefs.rows) {
      const detail = document.createElement("details");
      const summary = document.createElement("summary");
      summary.textContent = item.beliefId + " · " + item.companyId + " · 已确认合同数 "
        + (item.comparator === "AT_LEAST" ? "≥ " : "≤ ") + item.threshold + " · " + beliefLabel(item.verdict);
      const fields = document.createElement("dl"); fields.className = "grid";
      const pairs = [["形成时点", item.formedAtMillis], ["精确预测时点", item.targetAsOfMillis],
        ["置信程度", beliefLabel(item.confidenceBand)], ["实验置信分（满分1000）", item.confidencePermille],
        ["采纳来源", item.sourcePartyId || "自拟判断"], ["核验发布者", item.verificationPublisherPartyId],
        ["本人理由", item.rationale], ["实际已读证据", item.evidence.map(ref => ref.docId + " v" + ref.version + " · 本人阅读 " + ref.index).join("；")]];
      for (const [name, value] of pairs) {
        const term = document.createElement("dt"); term.textContent = name;
        const description = document.createElement("dd"); description.textContent = text(value);
        fields.append(term, description);
      }
      detail.append(summary, fields); $("belief-items").appendChild(detail);
    }
    fillTable("belief-sources", view.sources, (tr, row) => {
      cell(tr, row.sourcePartyId); cell(tr, beliefLabel(row.band)); cell(tr, row.competencePermille, "num");
    });
    fillTable("belief-habits", view.habits, (tr, row) => {
      cell(tr, beliefLabel(row.kind)); cell(tr, row.level, "num"); cell(tr, row.lastUpdatedMillis, "num");
    });
    fillTable("belief-evaluations", view.evaluations.rows, (tr, row) => {
      cell(tr, row.beliefId); cell(tr, row.docId + " v" + row.version); cell(tr, beliefLabel(row.verdict));
      cell(tr, row.factValue, "num"); cell(tr, row.maturedAtMillis, "num"); cell(tr, row.supersedesEvaluationId);
    });
    $("belief-evaluations-count").textContent = "显示 " + view.evaluations.rows.length + " / " + view.evaluations.total;
    $("belief-revisions-count").textContent = "显示 " + view.habitRevisions.rows.length + " / " + view.habitRevisions.total;
    for (const item of view.habitRevisions.rows) {
      const line = document.createElement("p");
      line.textContent = item.atMillis + " · " + beliefLabel(item.kind) + "：" + item.fromLevel + " → " + item.toLevel
        + " · 支持判断：" + (item.supportBeliefIds.join("、") || "无");
      $("belief-revisions").appendChild(line);
    }
  } catch (failure) {
    if (current()) { $("belief-status").textContent = ""; setMessage("belief-error", failure.message); }
  }
}
$("belief-refresh").addEventListener("click", () => loadBeliefs());

function renderClosedReadings() {
  const view = state.details.information;
  if (!view) return;
  const section = view.closedReadings || { total: 0, rows: [] };
  const mode = $("information-closed-filter").value;
  const rows = (section.rows || []).filter(row => mode === "ALL" || row.status === mode);
  fillTable("information-closed", rows, (tr, row) => {
    cell(tr, row.readId);
    cell(tr, row.personPartyId + (row.personName ? "（" + row.personName + "）" : ""));
    cell(tr, row.docId);
    cell(tr, row.version, "num");
    cell(tr, row.startedAt, "num");
    cell(tr, row.dueAt, "num");
    cell(tr, row.closedAt, "num");
    cell(tr, label("reading", row.status));
  });
  $("information-closed-empty").hidden = rows.length !== 0;
  $("information-closed-count").textContent = "显示 " + rows.length + " / 本页 " + (section.rows || []).length
    + " 条（总计 " + section.total + " 条）";
}

for (const [id, render] of [["company-report-filter", renderCompanyReports],
  ["performance-filter", renderPerformanceOwners],
  ["org-appointment-filter", renderAppointments],
  ["information-closed-filter", renderClosedReadings]]) {
  $(id).addEventListener("change", () => render());
}

for (const id of ["performance-history-owner", "performance-history-start",
  "performance-history-end"]) {
  $(id).addEventListener("change", () => clearPerformanceHistoryDom());
}
$("performance-history-query").addEventListener("click", () => {
  guard(loadPerformanceHistory());
});
$("performance-history-recent").addEventListener("click", () => {
  const position = state.details.performance ? state.details.performance.position : null;
  if (!position) return;
  const end = BigInt(position);
  const start = end - 399n > 1n ? end - 399n : 1n;
  $("performance-history-start").value = start.toString();
  $("performance-history-end").value = end.toString();
  clearPerformanceHistoryVisuals();
  guard(loadPerformanceHistory());
});

// 采编观察：管理员原件与个人真实经历分别由服务器投影，正文始终作为文字显示。
const MEDIA_TABLES = ["media-organizations", "media-stories", "media-works", "media-reports", "media-pending", "media-citations", "media-roots"];
const MEDIA_LABELS = { WIRE: "快讯", INVESTIGATIVE: "调查", DRAFT: "编辑中", SUBMITTED: "待审阅",
  RETURNED: "已退回", APPROVED: "待发布", PUBLISHED: "已发布", PUBLIC: "公开成稿",
  COMPLETED: "已完成", CANCELED: "已取消", NOT_AUTHORIZED: "当前任职无权操作",
  BUDGET: "工时不足", BUSY: "已有专注工作", VERSION: "版本已变化",
  EVIDENCE_NOT_READ: "证据尚未读完", EVIDENCE_NOT_RECALLABLE: "证据当前不可回忆",
  DRAFT_NOT_READ: "草稿尚未读完", DRAFT_NOT_RECALLABLE: "草稿当前不可回忆",
  MEDIA_STORY_PROPOSE_V1: "提出选题", MEDIA_STORY_SUBMIT_V1: "提交草稿",
  MEDIA_STORY_REVIEW_V1: "编辑审阅", MEDIA_STORY_PUBLISH_V1: "发布",
  MEDIA_STORY_CORRECTION_V1: "开启更正", MEDIA_WORK_CANCEL_V1: "取消工作" };
function mediaLabel(value) { return MEDIA_LABELS[value] || text(value); }
function mediaTable(id, section, columns) {
  fillTable(id, (section || {}).rows, (tr, row) => {
    for (const key of columns) {
      const value = key === "accepted" ? (row[key] ? "通过" : "拒绝")
        : key === "templates" ? Object.entries(row[key] || {}).map(([name, count]) => mediaLabel(name) + " · 至少 " + count + " 组原始来源").join("；")
        : ["kind", "template", "status", "outcome", "reason"].includes(key) ? mediaLabel(row[key]) : row[key];
      cell(tr, value);
    }
  });
  fillSection(id + "-empty", id + "-count", section);
}
function clearMediaStory() {
  state.mediaRequest += 1;
  $("media-versions").replaceChildren();
  $("media-story-cut").textContent = "";
  for (const id of ["media-citations", "media-roots"]) mediaTable(id, null, []);
  setMessage("media-story-error", "");
}
function clearMediaDom() {
  clearMediaStory();
  for (const id of MEDIA_TABLES) mediaTable(id, null, []);
  $("media-story-select").replaceChildren();
  $("media-story-select").disabled = true;
  $("media-cut").textContent = "";
  $("media-notes").replaceChildren();
  delete state.details.media;
}
function renderMedia(view) {
  clearMediaStory();
  $("media-cut").textContent = cutText(view, view.available ? "采编实验" : "本档案未启用采编");
  fillNotes("media-notes", view.notes);
  mediaTable("media-organizations", view.organizations, ["name", "templates", "totalMillis", "spentMillis", "reservedMillis", "availableMillis"]);
  mediaTable("media-stories", view.stories, ["organizationPartyId", "storyId", "template", "topic", "authorPartyId", "status", "revisionNo", "draftVersion", "publicVersion", "sourceGroups", "approvedBy"]);
  mediaTable("media-pending", view.pending, ["taskId", "personPartyId", "organizationPartyId", "kind", "dueTimeMillis"]);
  mediaTable("media-works", view.works, ["taskId", "personPartyId", "organizationPartyId", "kind", "storyId", "startedAtMillis", "completedAtMillis", "chargedMillis", "outcome"]);
  mediaTable("media-reports", view.reports, ["personPartyId", "kind", "storyId", "accepted", "reason", "timeMillis", "position"]);
  const select = $("media-story-select"); select.replaceChildren();
  const empty = document.createElement("option"); empty.value = ""; empty.textContent = "选择稿件查看原始版本"; select.appendChild(empty);
  (view.stories.rows || []).forEach((story, index) => {
    const option = document.createElement("option"); option.value = String(index);
    option.textContent = story.organizationPartyId + " · " + story.topic + " · " + story.storyId;
    select.appendChild(option);
  });
  select.disabled = !view.available || view.stories.rows.length === 0;
}
function renderMediaDocuments(id, section) {
  const list = $(id); list.replaceChildren();
  for (const item of (section || {}).rows || []) {
    const detail = document.createElement("details"), summary = document.createElement("summary");
    summary.textContent = (item.kind ? (item.kind === "DRAFT" ? "组织草稿" : mediaLabel(item.kind)) + " · " : "") + item.title + " · v" + item.version;
    const meta = document.createElement("p"); meta.className = "hint";
    meta.textContent = item.docId + " · 发布 " + item.publishedAtMillis + " 毫秒"
      + (item.completedAtMillis ? " · 本人读完 " + item.completedAtMillis + " 毫秒" : "");
    const source = document.createElement("p"); source.textContent = item.sourceDescription || "未提供自由文字来源描述";
    const body = document.createElement("pre"); body.className = "media-body"; body.textContent = item.body;
    detail.append(summary, meta, source, body); list.appendChild(detail);
  }
  const count = document.createElement("p"); count.className = "hint";
  count.textContent = "显示 " + ((section || {}).rows || []).length + " / 共 " + ((section || {}).total || 0) + " 个实际版本";
  list.appendChild(count);
}
async function loadMediaStory() {
  clearMediaStory();
  const view = state.details.media, selected = $("media-story-select").value;
  if (!view || selected === "" || state.mode !== "admin" || state.page !== "media") return;
  const story = view.stories.rows[Number(selected)];
  if (!story) return;
  const generation = state.generation, pageVersion = state.pageVersion, requestId = state.mediaRequest;
  const current = () => isCurrent(generation) && state.mode === "admin" && state.page === "media"
    && pageVersion === state.pageVersion && requestId === state.mediaRequest && selected === $("media-story-select").value;
  try {
    const result = await request("POST", "/api/admin/media-story", { organizationPartyId: story.organizationPartyId,
      storyId: story.storyId, expectedPosition: view.position });
    if (!current()) return;
    if (result.status === 401 || result.status === 403) { sessionLost(); return; }
    if (result.status !== 200 || !result.payload) throw new Error(result.payload && result.payload.message || "稿件读取失败，请刷新重试。");
    const data = result.payload;
    if (data.position !== view.position || data.storyId !== story.storyId || data.organizationPartyId !== story.organizationPartyId) throw new Error("稿件截止不一致，请刷新重试。");
    $("media-story-cut").textContent = cutText(data, "原始版本与实际引用");
    renderMediaDocuments("media-versions", data.versions);
    mediaTable("media-citations", data.citations, ["personPartyId", "docId", "version", "index"]);
    mediaTable("media-roots", data.roots, ["docId", "version"]);
  } catch (failure) { if (current()) setMessage("media-story-error", failure.message); }
}
function clearPersonalMedia() {
  $("person-media").hidden = true;
  for (const id of ["person-media-stories", "person-media-reports"]) mediaTable(id, null, []);
  $("person-media-documents").replaceChildren(); $("person-media-tools").textContent = "";
}
function renderPersonalMedia(media) {
  clearPersonalMedia(); if (!media) return;
  $("person-media").hidden = false;
  $("person-media-tools").textContent = "当前人物可用动作：" + (media.tools || []).join("、");
  mediaTable("person-media-stories", media.stories, ["storyId", "template", "topic", "authorPartyId", "status", "revisionNo", "sourceGroups"]);
  mediaTable("person-media-reports", media.reports, ["kind", "storyId", "accepted", "reason", "timeMillis"]);
  renderMediaDocuments("person-media-documents", media.documents);
}
async function refreshCapabilities(generation, current) {
  const result = await request("GET", "/api/admin/capabilities");
  if (!isCurrent(generation) || state.mode !== "admin" || !current()) return false;
  if (result.status === 401 || result.status === 403) { sessionLost(); return false; }
  if (result.status !== 200 || !result.payload) throw new Error("当前档案能力读取失败，请刷新。");
  const first = state.capabilities === null;
  state.capabilities = result.payload;
  const scenesNote = $("live-scenes-note");
  if (scenesNote) {
    const supported = result.payload.supportedScenes || [];
    scenesNote.textContent = supported.length
      ? "当前档案已接入执行器：" + supported.map(id => LIVE_SCENES[id] || id).join("、")
        + "。清单来自当前档案能力接口；未列出的场景只保存配置，保存不会产生模型调用。"
      : "当前档案没有接入场景执行器：保存配置不会产生模型调用。";
  }
  renderLivePersons(first ? "alice" : undefined);
  renderLiveSceneChoices();
  return true;
}
$("media-story-select").addEventListener("change", () => guard(loadMediaStory()));


guard(boot());


// 模型运行：只读轮询用于显示，服务端独立处理完成结果。
const LIVE_PHASES = { SCRIPTED: "脚本模式 · 未接模型", IDLE: "等待开始", READY: "等待开始",
  RUNNING: "本轮运行中", STOPPING: "等待已发请求结束", PAUSED: "已暂停处理", FAULT: "需要重启恢复" };
const LIVE_SCENES = { research: "机构研究", trading: "交易执行", governance: "公司治理",
  media: "媒体采编", evaluation: "评估实验（未接入）" };
function stopLivePoll() {
  if (state.liveTimer) clearTimeout(state.liveTimer);
  state.liveTimer = null;
  state.liveRequest += 1;
}
function clearLiveDom() {
  stopLivePoll();
  state.live = null;
  state.liveBusy = false;
  fillTable("live-scenes", [], () => undefined);
  fillTable("live-budgets", [], () => undefined);
  $("live-decisions").replaceChildren();
  $("live-skips").replaceChildren();
  $("live-round").textContent = "尚未开始本轮。";
  const scene = $("live-scene");
  if (scene) { scene.replaceChildren(); const auto = document.createElement("option");
    auto.value = ""; auto.textContent = "按人物原路由"; scene.appendChild(auto); scene.value = ""; }
  $("live-company").replaceChildren();
  $("live-company").value = "";
  $("live-company-field").hidden = true;
  $("live-scene-note").textContent = "";
  $("live-cut").textContent = "尚未读取运行状态。";
  $("live-phase").textContent = "—";
  $("live-summary").textContent = "";
  setMessage("live-error", "");
  setMessage("live-result", "");
  for (const action of ["start", "stop", "pause", "resume"]) $("live-" + action).disabled = true;
}
function liveButtons() {
  const v = state.live;
  const connected = v && v.phase !== "SCRIPTED" && v.phase !== "FAULT";
  $("live-start").disabled = state.liveBusy || !connected || !["IDLE", "READY"].includes(v.phase);
  $("live-stop").disabled = state.liveBusy || !connected || !(v.physicalInFlight || v.completionSlots || v.pendingPersons.length);
  $("live-pause").disabled = state.liveBusy || !connected || v.paused;
  $("live-resume").disabled = state.liveBusy || !connected || !v.paused;
}
/** 当前勾选的本轮人物（真实点击路径）。 */
function liveCheckedPersons() {
  return Array.from(document.querySelectorAll('input[name="live-person"]:checked'), node => node.value);
}
/** 单个人物可按能力的显式场景；缺字段时退回旧自动场景，不猜测。 */
function livePersonScenes(person) {
  if (!person) return [];
  return Array.isArray(person.scenes) && person.scenes.length ? person.scenes : [person.sceneId];
}
/** 人物能力行：自动路由名单与治理名单是两份真实数据，不能互相补人。 */
function liveSelected(personPartyId) {
  const payload = state.capabilities || {};
  const pools = [payload.persons || [], payload.governancePersons || []];
  for (const pool of pools) {
    for (const person of pool) {
      if (person.personPartyId === personPartyId) return person;
    }
  }
  return null;
}
/** 本轮可选人物池：显式公司治理才用真实席位/经理名单，其余（含默认自动路由）用旧名单。 */
function livePersonPool() {
  const payload = state.capabilities || {};
  const scene = ($("live-scene") && $("live-scene").value) || "";
  if (scene === "governance") return payload.governancePersons || [];
  return payload.persons || [];
}
/**
 * 重建人物勾选列表：换池时只保留仍在池里的人（切回自动路由会清掉纯董事），
 * 不伪造机构或四岗任职。
 */
function renderLivePersons(defaultId) {
  const list = $("live-persons");
  if (!list) return [];
  const pool = livePersonPool();
  const previous = new Set(liveCheckedPersons());
  let kept = pool.map(person => person.personPartyId).filter(id => previous.has(id));
  if (!kept.length && defaultId && pool.some(person => person.personPartyId === defaultId)) {
    kept = [defaultId];
  }
  list.replaceChildren();
  const legend = document.createElement("legend");
  legend.textContent = "本轮参与人物";
  list.appendChild(legend);
  for (const person of pool) {
    const label = document.createElement("label"), input = document.createElement("input");
    input.type = "checkbox"; input.name = "live-person"; input.value = person.personPartyId;
    input.checked = kept.includes(person.personPartyId);
    const scenes = livePersonScenes(person).map(id => LIVE_SCENES[id] || id).join("、");
    label.append(input, document.createTextNode(person.name + " · " + scenes));
    list.appendChild(label);
  }
  if (!pool.length) {
    const hint = document.createElement("p");
    hint.textContent = "当前场景没有可选人物。";
    list.appendChild(hint);
  }
  return kept;
}
/**
 * 重建“本轮场景”选项：只列当前档案已接入、且所选人物都能用的场景；默认按人物原路由。
 * 一个人都不选时只保留默认项，避免把“未选人物”误显示成场景不可用。
 */
function renderLiveSceneChoices(persons) {
  const select = $("live-scene");
  if (!select) return [];
  const people = persons !== undefined ? persons : liveCheckedPersons();
  const supported = (state.capabilities && state.capabilities.supportedScenes) || [];
  const usable = people.length === 0 ? [] : supported.filter(scene => people.every(id => {
    const person = liveSelected(id);
    return person !== null && livePersonScenes(person).includes(scene);
  }));
  const previous = select.value;
  select.replaceChildren();
  const auto = document.createElement("option");
  auto.value = ""; auto.textContent = "按人物原路由";
  select.appendChild(auto);
  for (const scene of usable) {
    const option = document.createElement("option");
    option.value = scene; option.textContent = LIVE_SCENES[scene] || scene;
    select.appendChild(option);
  }
  select.value = usable.includes(previous) ? previous : "";
  renderLiveCompanyChoices(people);
  return usable;
}
/** 治理轮可选公司：所选人物真实可治理公司的交集；无交集则不提供治理选项。 */
function renderLiveCompanyChoices(persons) {
  const select = $("live-company");
  const field = $("live-company-field");
  const note = $("live-scene-note");
  if (!select || !field) return [];
  const people = persons !== undefined ? persons : liveCheckedPersons();
  let companies = [];
  if (people.length) {
    for (const id of people) {
      const person = liveSelected(id);
      const own = (person && person.governanceCompanies) || [];
      companies = companies.length === 0 ? own.slice() : companies.filter(c => own.includes(c));
    }
  }
  const previous = select.value;
  select.replaceChildren();
  for (const company of companies) {
    const option = document.createElement("option");
    option.value = company; option.textContent = company;
    select.appendChild(option);
  }
  select.value = companies.includes(previous) ? previous : (companies[0] || "");
  const governance = $("live-scene").value === "governance";
  field.hidden = !governance;
  if (note) {
    note.textContent = governance
      ? (companies.length
        ? "本轮用“公司治理”配置：只影响模型、Key 与预算；人物的观察与工具仍由其真实席位/任职决定。"
        : "所选人物没有共同的公司：董事席位与经理任职都来自真实世界状态，不能在这里补授。")
      : "";
  }
  return companies;
}
/** 本轮开始请求体：只在显式选择场景时带上 scene；治理轮才带 companyId。 */
function liveStartBody(persons) {
  if (!state.live) return null;
  const body = { expectedPosition: state.live.position, persons };
  body.expectedRevision = state.routeRevision;
  const scene = ($("live-scene") && $("live-scene").value) || "";
  if (scene) {
    body.scene = scene;
    if (scene === "governance") {
      const company = ($("live-company") && $("live-company").value) || "";
      if (!company) { setMessage("live-error", "请选择本轮治理公司。"); return null; }
      body.companyId = company;
    }
  }
  return body;
}
/** 本轮摘要：场景/人员/公司都取服务端同一确认截止的值，不按界面状态猜测。 */
function liveRoundSummary(v) {
  if (!v || !v.roundPersons || v.roundPersons.length === 0) return "尚未开始本轮。";
  const scene = v.roundScene ? (LIVE_SCENES[v.roundScene] || v.roundScene) : "按人物原路由";
  return "本轮：" + scene + (v.roundCompany ? " · 公司 " + v.roundCompany : "")
    + " · 人物 " + v.roundPersons.join("、") + "（预算见下表）";
}
function renderLive(v) {
  state.live = v;
  renderAdminCut(v);
  $("live-cut").textContent = "运行 " + v.runId + " · 确认记录 " + v.position + " · 模拟时点 " + v.timeMillis + " 毫秒";
  $("live-phase").textContent = LIVE_PHASES[v.phase] || v.phase;
  $("live-round").textContent = liveRoundSummary(v);
  renderLiveSceneChoices();
  $("live-summary").textContent = v.phase === "FAULT" ? "本地记录保存失败，须关闭并重启恢复。普通继续不会解除故障。"
    : "尚未发起 " + v.pendingPersons.length + " 人 · 网络请求 " + v.physicalInFlight
      + " 个 · 结果处理占用 " + v.completionSlots + " 个";
  const budgets = v.runBudget ? [{ personPartyId: "整次实验", budget: v.runBudget }, ...v.personBudgets] : [];
  fillTable("live-budgets", budgets, (tr, row) => {
    const b = row.budget;
    cell(tr, row.personPartyId);
    cell(tr, b.usedCalls + " / " + b.reservedCalls + " / " + b.capCalls);
    cell(tr, b.usedInputTokens + " / " + b.reservedInputTokens + " / " + b.capInputTokens);
    cell(tr, b.usedOutputTokens + " / " + b.reservedOutputTokens + " / " + b.capOutputTokens);
  });
  fillTable("live-scenes", v.scenes, (tr, row) => {
    cell(tr, (LIVE_SCENES[row.sceneId] || row.sceneId) + (state.capabilities && state.capabilities.supportedScenes.includes(row.sceneId) ? "" : " · 当前档案未接入"));
    cell(tr, row.slotId + " · " + row.model + " · 版本 " + row.revision);
    cell(tr, row.enabled ? "允许" : "关闭");
    cell(tr, row.usedCalls + " / " + row.reservedCalls + " / " + row.capCalls);
    cell(tr, row.usedInputTokens + " / " + row.reservedInputTokens);
    cell(tr, row.usedOutputTokens + " / " + row.reservedOutputTokens);
    cell(tr, row.capTotalTokens);
  });
  $("live-skips").replaceChildren();
  for (const item of v.skips) {
    const li = document.createElement("li");
    li.textContent = item.personPartyId + "：" + item.reason;
    if (item.inputSize) {
      li.textContent += "（字符 " + item.inputSize.actualChars + " / " + item.inputSize.maxChars
        + "，UTF8字节 " + item.inputSize.actualBytes + " / " + item.inputSize.maxBytes + "）";
    }
    $("live-skips").appendChild(li);
  }
  if (!v.skips.length) {
    const li = document.createElement("li"); li.textContent = "本轮没有跳过记录。"; $("live-skips").appendChild(li);
  }
  const expanded = new Set(Array.from($("live-decisions").querySelectorAll("details[open]"), node => node.dataset.actionId));
  $("live-decisions").replaceChildren();
  for (const item of v.decisions) {
    const detail = document.createElement("details");
    detail.dataset.actionId = item.actionId;
    detail.open = expanded.has(item.actionId);
    const summary = document.createElement("summary");
    summary.textContent = item.personPartyId + " · " + item.status + " · " + text(item.outcome) + " · " + item.actionId;
    detail.appendChild(summary);
    const meta = document.createElement("p"); meta.className = "hint";
    meta.textContent = text(item.sceneId) + " / " + text(item.slotId) + " / " + text(item.model)
      + " · 冻结版本 " + text(item.revision) + " · 声明用量 输入 " + text(item.declaredInputTokens)
      + " / 输出 " + text(item.declaredOutputTokens) + " · 费用 " + text(item.costStatus)
      + (item.reason ? " · 原因 " + item.reason : "");
    detail.appendChild(meta);
    for (const [label, prefix] of [["实际输入", "input"], ["模型回执", "reply"]]) {
      const heading = document.createElement("h3");
      heading.textContent = label + "（" + item[prefix + "Length"] + " 字符"
        + (item[prefix + "Truncated"] ? "，仅显示前 4000 字符" : "") + "）";
      const pre = document.createElement("pre"); pre.textContent = item[prefix + "Preview"] || "尚无正文";
      detail.append(heading, pre);
    }
    $("live-decisions").appendChild(detail);
  }
  $("live-empty").hidden = v.decisions.length > 0;
  liveButtons();
}
async function refreshLive() {
  if (state.mode !== "admin" || state.page !== "models") return;
  stopLivePoll();
  const generation = state.generation, requestId = state.liveRequest;
  const current = () => isCurrent(generation) && state.mode === "admin" && state.page === "models" && requestId === state.liveRequest;
  try {
    if (!await refreshCapabilities(generation, current)) return;
    const response = await request("GET", "/api/admin/live");
    if (!current()) return;
    if (response.status === 401 || response.status === 403) { sessionLost(); return; }
    if (response.status !== 200 || !response.payload) throw new Error("模型运行状态读取失败（" + response.status + "）。");
    renderLive(response.payload);
    setMessage("live-error", "");
  } catch (failure) {
    if (current()) setMessage("live-error", failure.message);
  } finally {
    if (current()) state.liveTimer = setTimeout(() => { guard(refreshLive()); }, 2000);
  }
}
async function liveAction(action, event) {
  if (event) event.preventDefault();
  if (state.mode !== "admin" || !state.live || state.liveBusy) return;
  const generation = state.generation;
  let body;
  if (action !== "start") {
    body = { expectedPosition: state.live.position };
  } else {
    const persons = liveCheckedPersons();
    if (!persons.length) { setMessage("live-error", "请选择至少一位人物。"); return; }
    body = liveStartBody(persons);
    if (body === null) return; // liveStartBody 已给出具体原因（如未选治理公司）
  }
  state.liveBusy = true;
  liveButtons();
  try {
    const response = await request("POST", "/api/admin/live/" + action, body);
    if (!isCurrent(generation) || state.mode !== "admin") return;
    if (response.status === 401 || response.status === 403) { sessionLost(); return; }
    if (response.status !== 200) {
      setMessage("live-result", response.payload && response.payload.message || "操作未完成，请刷新核对。");
      if (response.status === 409) await refreshRoutes(generation);
    } else {
      setMessage("live-result", ({ start: "本轮已开始，实际派发与回报见下方。", stop: "已停止新调用，已发请求继续结算。",
        pause: "已暂停推进与结果处理。", resume: "已恢复处理。" })[action]);
    }
    await refreshLive();
  } finally {
    if (isCurrent(generation) && state.mode === "admin") { state.liveBusy = false; liveButtons(); }
  }
}
$("live-start-form").addEventListener("submit", event => guard(liveAction("start", event)));
// 人物勾选与场景选择都只重建本地选项；真正的资格与路由由服务端在同一确认截止判定。
$("live-persons").addEventListener("change", () => renderLiveSceneChoices());
// 换场景就换人物池：切到公司治理才出现纯董事，切回自动路由立即清掉不兼容的人。
$("live-scene").addEventListener("change", () => { renderLivePersons(); renderLiveCompanyChoices(); });
for (const action of ["stop", "pause", "resume"]) $("live-" + action).addEventListener("click", () => guard(liveAction(action)));

function clearInterviewDom() {
  for (const id of ["interview-invites", "interview-sessions", "interview-works", "interview-budgets"]) {
    fillTable(id, [], () => undefined);
  }
  for (const id of ["interview-invites-empty", "interview-sessions-empty", "interview-works-empty"]) {
    $(id).textContent = "";
  }
  for (const id of ["dialogue-sessions", "dialogue-turns", "dialogue-closes", "dialogue-reports"]) {
    fillTable(id, [], () => undefined);
  }
  if ($("dialogue-note")) $("dialogue-note").textContent = "";
  state.dialogue = null;
  delete state.details.interview;
}

/** 逐轮访谈审计：轮次、关闭原因与双方真实阅读状态。 */
async function loadDialogue(generation, pageVersion) {
  let result;
  try {
    result = await request("GET", "/api/admin/dialogue");
  } catch (failure) {
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin"
      || pageVersion !== state.pageVersion || state.page !== "interview") return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) return;
  state.dialogue = result.payload;
  renderDialogue(result.payload);
}

function renderDialogue(view) {
  const sessions = (view && view.sessions) || [];
  const turns = (view && view.turns) || [];
  const closes = (view && view.closes) || [];
  fillSection("dialogue-sessions-empty", "dialogue-sessions-count", sessions);
  fillTable("dialogue-sessions", sessions.rows || [], (tr, row) => {
    cell(tr, row.sessionId);
    cell(tr, row.requestId);
    cell(tr, row.organizationPartyId);
    cell(tr, row.startedAtMillis, "num");
    cell(tr, row.endedAtMillis, "num");
    cell(tr, row.open ? "是" : "否");
  });
  fillTable("dialogue-turns", turns.rows || [], (tr, row) => {
    cell(tr, row.sessionId);
    cell(tr, row.sequence, "num");
    cell(tr, row.speakerPartyId);
    cell(tr, row.peerPartyId);
    cell(tr, row.turnId);
    cell(tr, row.docId);
    cell(tr, row.docVersion, "num");
    cell(tr, row.publishedAtMillis, "num");
    cell(tr, row.speakerRead ? "已读" : "未读");
    cell(tr, row.peerRead ? "已读" : "未读");
    cell(tr, row.title);
    cell(tr, row.body);
  });
  fillTable("dialogue-closes", closes.rows || [], (tr, row) => {
    cell(tr, row.sessionId);
    cell(tr, row.reason);
  });
  const reports = (view && view.reports) || [];
  fillTable("dialogue-reports", reports.rows || [], (tr, row) => {
    cell(tr, row.taskId);
    cell(tr, row.personPartyId);
    cell(tr, row.kind);
    cell(tr, row.accepted ? "已接受" : "已拒绝");
    cell(tr, row.reason);
    cell(tr, row.requestId);
    cell(tr, row.timeMillis, "num");
  });
  $("dialogue-note").textContent = (view && view.notes ? view.notes.join(" ") : "")
    + (view && view.available ? "" : " 当前档案未启用逐轮访谈。");
}

function clearEvidenceDom() {
  for (const id of ["evidence-roots", "evidence-aliases", "evidence-shares"]) {
    fillTable(id, [], () => undefined);
  }
  if ($("evidence-note")) $("evidence-note").textContent = "";
  state.evidence = null;
}

/** 管理员证据审计：真实根、公开标签与实际共享。 */
async function loadEvidence(generation, pageVersion) {
  let result;
  try {
    result = await request("GET", "/api/admin/evidence");
  } catch (failure) {
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin"
      || pageVersion !== state.pageVersion || state.page !== "evidence") return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) return;
  state.evidence = result.payload;
  renderEvidence(result.payload);
}

function renderEvidence(view) {
  const roots = (view && view.roots && view.roots.rows) || [];
  const aliases = (view && view.aliases && view.aliases.rows) || [];
  const shares = (view && view.shares && view.shares.rows) || [];
  fillTable("evidence-roots", roots, (tr, row) => {
    cell(tr, row.organizationPartyId);
    cell(tr, row.docId);
    cell(tr, row.version, "num");
    cell(tr, row.aliasCount, "num");
    cell(tr, row.shareCount, "num");
  });
  fillTable("evidence-aliases", aliases, (tr, row) => {
    cell(tr, row.organizationPartyId);
    cell(tr, row.storyId);
    cell(tr, row.revisionNo, "num");
    cell(tr, row.docId);
    cell(tr, row.version, "num");
    cell(tr, row.publicLabel);
    cell(tr, row.publicDocId || "—");
  });
  fillTable("evidence-shares", shares, (tr, row) => {
    cell(tr, row.organizationPartyId);
    cell(tr, row.shareId);
    cell(tr, row.docId);
    cell(tr, row.version, "num");
    cell(tr, row.recipientPartyId);
    cell(tr, row.sharingDocId);
    cell(tr, row.sharingVersion, "num");
    cell(tr, row.recipientRead ? "已读" : "未读");
  });
  $("evidence-note").textContent = (view && view.notes ? view.notes.join(" ") : "")
    + (view && view.available ? "" : " 当前档案未启用证据模块。");
}

function clearPersonalEvidence() {
  if ($("person-evidence")) $("person-evidence").hidden = true;
  for (const id of ["person-evidence-labels", "person-evidence-shares"]) {
    if ($(id)) fillTable(id, [], () => undefined);
  }
  if ($("person-evidence-note")) $("person-evidence-note").textContent = "";
  state.personEvidence = null;
}

/** 本人证据：只渲染本人实际读到的公开标签与共享说明。 */
async function renderPersonalEvidence(generation) {
  let result;
  try {
    result = await request("GET", "/api/me/evidence");
  } catch (failure) {
    return;
  }
  if (!isCurrent(generation) || state.mode !== "person") return;
  if (result.status === 401) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) return;
  const view = result.payload;
  state.personEvidence = view;
  const labels = (view.labels && view.labels.rows) || [];
  const shares = (view.shares && view.shares.rows) || [];
  fillTable("person-evidence-labels", labels, (tr, row) => {
    cell(tr, row.storyId);
    cell(tr, row.publicDocId);
    cell(tr, row.publicVersion, "num");
    cell(tr, row.publicLabel);
  });
  fillTable("person-evidence-shares", shares, (tr, row) => {
    cell(tr, row.title);
    cell(tr, row.body);
    cell(tr, row.sharingDocId);
    cell(tr, row.sharingVersion, "num");
  });
  const panel = $("person-evidence");
  if (panel) panel.hidden = !(view.available && (labels.length || shares.length));
  if ($("person-evidence-note")) {
    $("person-evidence-note").textContent = (view.notes || []).join(" ");
  }
}

/** 消息状态的中文口径：未送达对外不区分原因。 */
function socialStatus(status) {
  if (status === "QUEUED") return "待投递";
  if (status === "DELIVERED") return "已送达";
  return "未送达";
}

function clearSocialDom() {
  for (const id of ["social-messages", "social-blocks", "social-reports", "social-contacts"]) {
    if ($(id)) fillTable(id, [], () => undefined);
  }
  if ($("social-note")) $("social-note").textContent = "";
  state.social = null;
}

/** 管理员消息审计：真实送达、真实原因与收件人阅读状态。 */
async function loadSocial(generation, pageVersion) {
  let result;
  try {
    result = await request("GET", "/api/admin/social");
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "admin"
        && pageVersion === state.pageVersion && state.page === "social") {
      clearSocialDom();
      $("social-note").textContent = "读取失败，请稍后刷新重试。";
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin"
      || pageVersion !== state.pageVersion || state.page !== "social") return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) {
    clearSocialDom();
    $("social-note").textContent = "读取失败（" + result.status + "），请稍后刷新重试。";
    return;
  }
  state.social = result.payload;
  renderSocial(result.payload);
}

function renderSocial(view) {
  const messages = (view && view.messages && view.messages.rows) || [];
  const blocks = (view && view.blocks && view.blocks.rows) || [];
  const reports = (view && view.reports && view.reports.rows) || [];
  const contacts = (view && view.contacts && view.contacts.rows) || [];
  fillTable("social-messages", messages, (tr, row) => {
    cell(tr, row.messageId);
    cell(tr, row.from);
    cell(tr, row.to);
    cell(tr, row.channel);
    cell(tr, socialStatus(row.status));
    cell(tr, row.title);
    cell(tr, row.sentAt, "num");
    cell(tr, row.dueAt, "num");
    cell(tr, row.readByReceiver ? "已读" : "未读");
    cell(tr, row.rejectReason || "—");
  });
  fillTable("social-blocks", blocks, (tr, row) => {
    cell(tr, row.ownerPartyId);
    cell(tr, row.targetPartyId);
    cell(tr, row.since, "num");
    cell(tr, row.commandId);
  });
  fillTable("social-reports", reports, (tr, row) => {
    cell(tr, row.taskId);
    cell(tr, row.person);
    cell(tr, row.kind);
    cell(tr, row.accepted ? "已接受" : "已拒绝");
    cell(tr, row.reason);
    cell(tr, row.messageId);
    cell(tr, row.atMillis, "num");
  });
  fillTable("social-contacts", contacts, (tr, row) => {
    cell(tr, row.targetPartyId);
    cell(tr, row.channel);
    cell(tr, row.description);
    cell(tr, row.docId);
    cell(tr, row.version, "num");
  });
  $("social-note").textContent = (view && view.notes ? view.notes.join(" ") : "")
    + (view && view.available ? "" : " 当前档案未启用消息模块。");
}

function clearPersonalSocial() {
  if ($("person-social")) $("person-social").hidden = true;
  for (const id of ["person-social-inbox", "person-social-outbox", "person-social-contacts"]) {
    if ($(id)) fillTable(id, [], () => undefined);
  }
  if ($("person-social-note")) $("person-social-note").textContent = "";
  state.personSocial = null;
}

/** 本人消息：已送达信封（正文需本人读完）、发件结果与合法联系人。 */
async function renderPersonalSocial(generation) {
  let result;
  try {
    result = await request("GET", "/api/me/social");
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "person") {
      clearPersonalSocial();
      if ($("person-social")) {
        $("person-social").hidden = false;
        $("person-social-note").textContent = "读取失败，请稍后刷新重试。";
      }
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== "person") return;
  if (result.status === 401) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) {
    clearPersonalSocial();
    if ($("person-social")) {
      $("person-social").hidden = false;
      $("person-social-note").textContent = "读取失败（" + result.status + "），请稍后刷新重试。";
    }
    return;
  }
  const view = result.payload;
  state.personSocial = view;
  const inbox = (view.inbox && view.inbox.rows) || [];
  const outbox = (view.outbox && view.outbox.rows) || [];
  const contacts = (view.contacts && view.contacts.rows) || [];
  fillTable("person-social-inbox", inbox, (tr, row) => {
    cell(tr, row.from);
    cell(tr, row.channel);
    cell(tr, row.title);
    cell(tr, row.deliveredAt, "num");
    cell(tr, row.read ? "已读" : "未读");
    cell(tr, !row.read ? "" : (row.bodyAvailable === false
      ? "已读，当前无法回忆正文" : row.body));
  });
  fillTable("person-social-outbox", outbox, (tr, row) => {
    cell(tr, row.to);
    cell(tr, row.channel);
    cell(tr, row.sentAt, "num");
    cell(tr, row.dueAt, "num");
    cell(tr, socialStatus(row.status));
  });
  fillTable("person-social-contacts", contacts, (tr, row) => {
    cell(tr, row.targetPartyId);
    cell(tr, row.channel);
    cell(tr, row.description);
  });
  const panel = $("person-social");
  if (panel) panel.hidden = !(view.available && (inbox.length || outbox.length || contacts.length));
  if ($("person-social-note")) {
    $("person-social-note").textContent = (view.notes || []).join(" ");
  }
}

function windowText(start, end) {
  if (start === undefined || start === null || end === undefined || end === null) return "—";
  return start + "–" + end;
}

function renderInterviewAudit(view) {
  const invites = (view && view.invites) || [];
  fillTable("interview-invites", invites, (tr, row) => {
    cell(tr, row.requestId);
    cell(tr, row.organizationPartyId);
    cell(tr, row.fromPartyId);
    cell(tr, row.toPartyId);
    cell(tr, row.topic);
    cell(tr, row.status);
    cell(tr, windowText(row.windowStartMillis, row.windowEndMillis));
    cell(tr, row.startMillis >= 0 ? row.startMillis : "—", "num");
    cell(tr, row.durationMillis, "num");
  });
  $("interview-invites-empty").textContent = invites.length ? "" : "当前没有预约记录。";

  const sessions = (view && view.sessions) || [];
  fillTable("interview-sessions", sessions, (tr, row) => {
    cell(tr, row.sessionId);
    cell(tr, row.requestId);
    cell(tr, row.startedAtMillis, "num");
    cell(tr, row.endedAtMillis >= 0 ? row.endedAtMillis : "进行中", "num");
    cell(tr, row.close || "—");
  });
  $("interview-sessions-empty").textContent = sessions.length ? "" : "当前没有会话记录。";

  const works = (view && view.works) || [];
  fillTable("interview-works", works, (tr, row) => {
    cell(tr, row.taskId);
    cell(tr, row.personPartyId);
    cell(tr, row.organizationPartyId);
    cell(tr, row.kind);
    cell(tr, row.startedAtMillis, "num");
    cell(tr, row.completedAtMillis, "num");
    cell(tr, row.chargedMillis, "num");
    cell(tr, row.outcome);
  });
  $("interview-works-empty").textContent = works.length ? "" : "当前没有采访工时记录。";

  fillTable("interview-budgets", (view && view.budgets) || [], (tr, row) => {
    cell(tr, row.organizationPartyId);
    cell(tr, row.totalMillis, "num");
    cell(tr, row.spentMillis, "num");
    cell(tr, row.reservedMillis, "num");
  });
}

function clearPersonalInterview() {
  const panel = $("person-interview");
  if (panel) panel.hidden = true;
  for (const id of ["person-interview-invites", "person-interview-contacts",
    "person-dialogue-sessions", "person-dialogue-turns"]) {
    fillTable(id, [], () => undefined);
  }
  $("person-interview-tools").textContent = "";
  $("person-interview-invites-empty").textContent = "";
  $("person-interview-contacts-empty").textContent = "";
  if ($("person-dialogue-note")) $("person-dialogue-note").textContent = "";
  state.personDialogue = null;
}

/** 本人逐轮访谈：未读轮次只有元数据，正文为空（服务器已裁剪）。 */
async function renderPersonalDialogue(generation) {
  let result;
  try {
    result = await request("GET", "/api/me/dialogue");
  } catch (failure) {
    return;
  }
  if (!isCurrent(generation) || state.mode !== "person") return;
  if (result.status === 401) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) return;
  const view = result.payload;
  state.personDialogue = view;
  const sessions = (view.sessions && view.sessions.rows) || [];
  const turns = (view.turns && view.turns.rows) || [];
  fillTable("person-dialogue-sessions", sessions, (tr, row) => {
    cell(tr, row.sessionId);
    cell(tr, row.peerPartyId);
    cell(tr, row.startedAtMillis, "num");
    cell(tr, row.endedAtMillis, "num");
    cell(tr, row.open ? "是" : "否");
    cell(tr, row.closeReason || "—");
  });
  fillTable("person-dialogue-turns", turns, (tr, row) => {
    cell(tr, row.sessionId);
    cell(tr, row.sequence, "num");
    cell(tr, row.mine ? "本人" : "对方");
    cell(tr, row.docId);
    cell(tr, row.docVersion, "num");
    cell(tr, row.readByMe ? "已读" : "未读");
    cell(tr, row.readByPeer ? "已读" : "未读");
    cell(tr, row.readByMe ? row.title : "");
    cell(tr, row.readByMe ? row.body : "");
  });
  const panel = $("person-interview");
  if (panel && view.available && (sessions.length || turns.length)) panel.hidden = false;
  if ($("person-dialogue-note")) {
    $("person-dialogue-note").textContent = (view.notes || []).join(" ");
  }
}

async function renderPersonalInterview(tools, generation) {
  const panel = $("person-interview");
  if (!panel) return;
  const actions = (tools || []).filter(tool => String(tool).startsWith("interview."));
  $("person-interview-tools").textContent = actions.length
    ? "可用采访动作：" + actions.join("、") : "";
  let result;
  try {
    result = await request("GET", "/api/me/interview");
  } catch (failure) {
    return;
  }
  if (!isCurrent(generation) || state.mode !== "person") return;
  if (result.status === 401) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) return;
  const view = result.payload;
  const invites = view.invites || [];
  const contacts = view.contacts || [];
  panel.hidden = !(invites.length || contacts.length || actions.length);
  fillTable("person-interview-invites", invites, (tr, row) => {
    const other = row.fromPartyId === view.personPartyId ? row.toPartyId : row.fromPartyId;
    cell(tr, row.requestId);
    cell(tr, other);
    cell(tr, row.topic);
    cell(tr, row.status);
    cell(tr, windowText(row.windowStartMillis, row.windowEndMillis));
    cell(tr, row.startMillis >= 0 ? row.startMillis : "—", "num");
    cell(tr, row.durationMillis, "num");
  });
  $("person-interview-invites-empty").textContent = invites.length
    ? "" : "当前没有本人参与的预约。";
  fillTable("person-interview-contacts", contacts, (tr, row) => {
    cell(tr, row.targetPartyId);
    cell(tr, row.channel);
    cell(tr, row.description);
  });
  $("person-interview-contacts-empty").textContent = contacts.length
    ? "" : "本人尚未通过实际阅读取得可私信的联系入口。";
}

// ---------------------------------------------------------------- 工程经营与回款（18c 第一切片）

// ---------------------------------------------------------------- 工程动作字段目录
// 只按 WorldWebHost.dispatchEngineering 的真实用法声明每个工具需要的字段；
// 页面上不出现的字段绝不会被发送出去（防止旧值混入新请求）。

const ENGINEERING_FIELD_SPECS = {
  contractId: {
    id: "eng-contract", label: "合同标识", hint: "例如 B-1；必须是本人实际读过机会后签下的合同。"
  },
  workId: {
    id: "eng-work", label: "开工单号", hint: "本次施工的工单号；同一合同可多次开工。"
  },
  units: {
    id: "eng-units", label: "数量（件）", hint: "开工或报验的单位数；必须是正整数。"
  },
  trancheIndex: {
    id: "eng-tranche", label: "分期序号", hint: "从 0 开始；0 是合法值，会按 0 提交。"
  },
  amount: {
    id: "eng-amount", label: "金额（元）", hint: "最多两位小数；只作为动作参数，不在这里改账。"
  },
  purpose: {
    id: "eng-purpose", label: "用途／依据", hint: "例如终止原因、核销依据；会写入动作证据。"
  },
  authorizationRef: {
    id: "eng-ref", label: "董事会豁免授权引用", hint: "由董事会签发；经理不能自填授权。"
  },
  durationMillis: {
    id: "eng-duration", label: "本次决策工时（毫秒）", hint: "占用本人的正工时；0 表示只占一个时点。"
  },
  meetingId: {
    id: "eng-meeting", label: "会议标识", hint: "必须是自己召集或参与的会议。",
    conveneHint: "本次要新召集的会议标识；由你指定且不能与已有会议重复。"
  },
  materialDeadline: {
    id: "eng-material-deadline", label: "材料截止时点", hint: "世界毫秒；到此为止可以提交材料。"
  },
  opensAt: {
    id: "eng-opens", label: "开会时点", hint: "世界毫秒；到此时材料冻结并开始表决。"
  },
  closesAt: {
    id: "eng-closes", label: "表决截止时点", hint: "世界毫秒；必须晚于开会时点。"
  },
  recused: {
    id: "eng-recused", label: "是否自主回避", kind: "select",
    options: [{ value: "", label: "否" }, { value: "true", label: "是（关联议题）" }],
    hint: "涉及本人关联事项时应选择回避。"
  },
  materialId: {
    id: "eng-material", label: "会议材料标识", hint: "本次会议内的材料名，不得与已有材料重名。"
  },
  docId: {
    id: "eng-doc", label: "材料来源资料",
    hint: "必须指向真实存在的资料标识；本公司有公开治理说明时会默认填入它，可改选其它真实资料。"
  },
  docVersion: {
    id: "eng-version", label: "资料版本号", hint: "固定引用的版本；默认 1。"
  },
  vote: {
    id: "eng-vote", label: "表决选项", kind: "select",
    options: [{ value: "", label: "请选择" }, { value: "YES", label: "赞成" },
      { value: "NO", label: "反对" }, { value: "ABSTAIN", label: "弃权" }],
    hint: "每人一票；未参与或已回避的席位不能投票。"
  },
  appointmentId: {
    id: "eng-appointment", label: "任免记录标识", hint: "本次任免的记录号，不可与历史重复。"
  },
  appointPersonId: {
    id: "eng-appoint-person", label: "被任免人", hint: "按人物标识填写，例如 dave。"
  },
  appointRole: {
    id: "eng-appoint-role", label: "岗位", kind: "select",
    options: [{ value: "COMPANY_MANAGER", label: "公司经理" }],
    hint: "当前实验只支持公司经理岗位。"
  },
  appointAction: {
    id: "eng-appoint-action", label: "任免动作", kind: "select",
    options: [{ value: "", label: "请选择" }, { value: "APPOINT", label: "任命" },
      { value: "REVOKE", label: "撤销" }],
    hint: "撤销时不需要填写期限。"
  },
  effectiveAt: {
    id: "eng-effective", label: "任职生效时点", hint: "世界毫秒；任命从此时点开始，撤销在此生效。"
  },
  endsAt: {
    id: "eng-ends", label: "任职期限（不含）", hint: "世界毫秒；必须晚于生效时点。"
  },
  waiverId: {
    id: "eng-waiver", label: "豁免请求标识", hint: "董事会正在审议的具体债权编号。"
  },
  customerPartyId: {
    id: "eng-customer", label: "客户主体标识", hint: "本次被豁免债权对应的客户账户主体。"
  },
  laborLimit: {
    id: "eng-labor", label: "人工预算上限（元）", hint: "留空表示本类不设上限；与采购上限至少填一个。"
  },
  procurementLimit: {
    id: "eng-procurement", label: "采购预算上限（元）", hint: "留空表示本类不设上限。"
  },
  period: {
    id: "eng-period", label: "预算期间序号", hint: "从 0 开始；0 是合法值，会按 0 提交。"
  },
  periodicPeriod: {
    id: "eng-period", label: "考核期间", kind: "select", source: "periods",
    hint: "只列本公司真实采样过的期间；期间序号从 1 开始。"
  },
  note: {
    id: "eng-note", label: "更正说明（可选）",
    hint: "留空表示首版；填写后会作为新草稿正文的更正说明，不改旧版。"
  },
  draftId: {
    id: "eng-draft", label: "草稿", kind: "select", source: "drafts",
    hint: "只能从本公司真实存在的草稿中选择；准备者不能批准自己的稿。"
  },
  draftVersion: {
    id: "eng-draft-version", label: "草稿版本",
    hint: "与所选草稿一致的版本号；选草稿后自动填入，也可按真实值修改。"
  },
  publisherPersonId: {
    id: "eng-publisher", label: "指定发布人", kind: "select", source: "publishers",
    hint: "授权只指定一名本公司董事；实际发布仍由该人自己的会话执行。"
  },
  expiresAt: {
    id: "eng-expires", label: "授权失效时点（世界毫秒）",
    hint: "右开区间；必须晚于当前世界时点，过期后不能发布。"
  },
  authorizationId: {
    id: "eng-auth", label: "授权标识（可选）", kind: "select", source: "authorizations",
    hint: "留空表示由服务端取该草稿唯一未消费授权；不能复用已消费授权。"
  }
};

const ENGINEERING_TOOL_FIELDS = {
  "engineering.sign": {
    fields: ["contractId"], required: ["contractId"],
    note: "签约只用到合同标识：合同条款来自冻结机会，不能在这里改价。"
  },
  "engineering.startWork": {
    fields: ["contractId", "workId", "units"], required: ["contractId", "workId", "units"],
    note: "开工按阶段数量报工，需要合同、工单号和件数。"
  },
  "engineering.invoice": {
    fields: ["contractId"], required: ["contractId"],
    note: "开票只用到合同标识；必须先有客户真实验收。"
  },
  "engineering.requestAcceptance": {
    fields: ["contractId", "trancheIndex", "units"], required: ["contractId", "trancheIndex", "units"],
    note: "请求验收只登记意图；客户是否验收由真实回应决定。"
  },
  "engineering.requestPayment": {
    fields: ["contractId", "trancheIndex"], required: ["contractId", "trancheIndex"],
    note: "请求付款只登记意图；到账以真实付款记录为准。"
  },
  "engineering.requestRetention": {
    fields: ["contractId", "trancheIndex"], required: ["contractId", "trancheIndex"],
    note: "请求质保金同样只是请求；质保到期才可能被支付。"
  },
  "engineering.impair": {
    fields: ["contractId", "amount"], required: ["contractId", "amount"],
    note: "计提减值只影响减值准备，不改合同收入。"
  },
  "engineering.writeOff": {
    fields: ["contractId", "amount"], required: ["contractId", "amount"],
    note: "核销不能超过已计提的减值准备。"
  },
  "engineering.recover": {
    fields: ["contractId", "amount"], required: ["contractId", "amount"],
    note: "请求追索回款：这是追索动作，不是到账；到账看付款记录。"
  },
  "engineering.paySupplier": {
    fields: ["amount", "purpose"], required: ["amount"],
    note: "支付供应商应付，按汇总口径扣现金与应付。"
  },
  "engineering.terminate": {
    fields: ["contractId", "purpose"], required: ["contractId"],
    note: "终止只拦后续履约，不回滚已发生成本。"
  },
  "engineering.waive": {
    fields: ["contractId", "amount", "purpose", "authorizationRef", "durationMillis"],
    required: ["contractId", "amount", "purpose", "authorizationRef", "durationMillis"],
    note: "执行董事会已签发的豁免：金额、合同、用途必须与授权原文一致。"
  },
  "periodic.attend": {
    fields: ["periodicPeriod", "recused"], required: ["periodicPeriod"],
    note: "登记出席本期真实考核会议；会议必须仍处于可登记状态，关联事项应选回避。"
  },
  "periodic.prepare": {
    fields: ["periodicPeriod", "note"], required: ["periodicPeriod"],
    note: "按期末快照生成准确草稿并冻结董事会材料；只有现任经理可用，首版不写更正说明。"
  },
  "periodic.score": {
    fields: ["periodicPeriod"], required: ["periodicPeriod"],
    note: "每个维度单独给分并选择本期合格证据；没把握的维度留空表示缺项，不补 0。"
  },
  "periodic.approve": {
    fields: ["periodicPeriod", "draftId", "draftVersion"],
    required: ["periodicPeriod", "draftId", "draftVersion"],
    note: "只批准本人实际读完的确切草稿版本；准备者不能批准自己的稿。"
  },
  "periodic.authorize": {
    fields: ["periodicPeriod", "draftId", "draftVersion", "publisherPersonId", "expiresAt"],
    required: ["periodicPeriod", "draftId", "draftVersion", "publisherPersonId", "expiresAt"],
    note: "至少三名董事已批准后才能签发；授权绑定公司、期间、草稿版本与正文，只消费一次。"
  },
  "periodic.publish": {
    fields: ["periodicPeriod", "draftId", "draftVersion", "authorizationId"],
    required: ["periodicPeriod", "draftId", "draftVersion"],
    note: "由被授权本人发布公开正文；发布后不可回填旧版本，逾期会记为迟报。"
  },
  "board.conveneAppointment": {
    fields: ["meetingId", "materialDeadline", "opensAt", "closesAt"],
    required: ["meetingId", "materialDeadline", "opensAt", "closesAt"],
    note: "只有章程主席能召集经理任免议题；会议号由你指定，不自动创立。任免仍需真实到会、读材料、投票与实施。"
  },
  "board.convene": {
    fields: ["meetingId", "materialDeadline", "opensAt", "closesAt"],
    required: ["meetingId", "materialDeadline", "opensAt", "closesAt"],
    note: "召集本身也是排程：到期才建会。会议号由你指定，不会自动创建。"
  },
  "board.conveneWaiver": {
    fields: ["meetingId", "materialDeadline", "opensAt", "closesAt"],
    required: ["meetingId", "materialDeadline", "opensAt", "closesAt"],
    note: "只对本公司主席开放，且服务端会再次核对主席身份。"
  },
  "board.attend": {
    fields: ["meetingId", "recused"], required: ["meetingId"],
    note: "参与登记占用本人正工时；关联议题应选回避。"
  },
  "board.addMaterial": {
    fields: ["meetingId", "materialId", "docId", "docVersion"],
    required: ["meetingId", "materialId", "docId", "docVersion"],
    note: "材料必须指向真实资料与固定版本，不能凭空造材料。"
  },
  "board.vote": {
    fields: ["meetingId", "vote"], required: ["meetingId", "vote"],
    note: "表决前必须实际完成固定版本材料的阅读。"
  },
  "board.proposeBudget": {
    fields: ["meetingId", "laborLimit", "procurementLimit", "period"],
    required: ["meetingId"],
    note: "人工与采购上限至少填一个；期间 0 是合法值。"
  },
  "board.proposeAppointment": {
    fields: ["meetingId", "appointmentId", "appointPersonId", "appointRole",
      "appointAction", "effectiveAt", "endsAt"],
    required: ["meetingId", "appointmentId", "appointPersonId", "appointAction", "effectiveAt"],
    note: "任命需要期限；撤销时期限留空即可。授权≠任职，实施后才生效。"
  },
  "board.proposeWaiver": {
    fields: ["meetingId", "waiverId", "contractId", "customerPartyId", "amount", "purpose"],
    required: ["meetingId", "waiverId", "contractId", "customerPartyId", "amount", "purpose"],
    note: "提案冻结金额与用途；董事会只能批准或否决这份原文。"
  },
  "board.implement": {
    fields: ["meetingId"], required: ["meetingId"],
    note: "实施已通过的决议；未通过时会被真实拒绝。"
  }
};

const ENGINEERING_TOOL_LABELS = {
  "board.convene": "召集会议",
  "board.conveneWaiver": "召集豁免会议",
  "board.attend": "参与登记",
  "board.addMaterial": "提交材料",
  "board.vote": "表决",
  "board.proposeBudget": "预算提案",
  "board.proposeAppointment": "任免提案",
  "board.proposeWaiver": "豁免提案",
  "board.implement": "实施决议",
  "engineering.sign": "签约",
  "engineering.startWork": "开工",
  "engineering.invoice": "开票",
  "engineering.terminate": "终止合同",
  "engineering.paySupplier": "支付供应商应付",
  "engineering.impair": "计提减值",
  "engineering.writeOff": "核销",
  "engineering.recover": "请求追索回款",
  "engineering.requestAcceptance": "请求客户验收",
  "engineering.requestPayment": "请求客户付款",
  "engineering.requestRetention": "请求质保金",
  "engineering.waive": "执行董事会豁免",
  "board.conveneAppointment": "召集经理任免会议",
  "periodic.attend": "登记周期会议出席",
  "periodic.prepare": "准备周期财报",
  "periodic.score": "提交本席评分",
  "periodic.approve": "批准确切草稿版本",
  "periodic.authorize": "签发披露授权",
  "periodic.publish": "发布公开财报"
};

// 报表字段顺序固定；未出现的键不显示，未实现的口径显式标注，不写 0。
const ENGINEERING_REPORT_LABELS = [
  ["companyCash", "公司现金（唯一经营账户）"],
  ["arCurrent", "应收（当期，已开票未到期）"],
  ["arRetention", "应收（质保金）"],
  ["payable", "应付供应商（汇总口径）"],
  ["allowance", "减值准备"],
  ["memo", "备查金额"],
  ["obligation", "客户义务（合同义务合计）"],
  ["revenue", "已确认收入（按合同确认）"],
  ["cogs", "已确认成本"],
  ["impairmentLoss", "减值损失"],
  ["recoveryIncome", "回收收益"],
  ["terminationLoss", "终止损失"],
  ["waiverLoss", "豁免损失"],
  ["equity", "账面权益"],
  ["balanced", "账目是否对平"],
  ["contractCount", "合同数"],
  ["workOrderCount", "工单数"],
  ["meetingCount", "董事会会议数"],
  ["charterQuorum", "法定人数"],
  ["chairSeatId", "主席席位"],
  ["customerAccountId", "客户账户"],
  ["period", "当前客户期间"],
  ["periodBudgetLeft", "期间预算余量（空＝本档未声明预算）"],
  ["periodClosed", "期间已关闭"],
  ["payableScope", "应付口径"],
  ["customerScope", "客户池口径"],
  ["workOrderScope", "工单口径"],
  ["retentionScope", "质保口径"],
  ["memoScope", "备查口径"],
  ["templateNote", "经营模板"]
];

const ENGINEERING_SCOPE_TEXT = {
  AGGREGATE_SUPPLIER: "聚合（累计发生−累计支付）",
  NOT_APPLICABLE: "不适用于该经营模板",
  MANUFACTURING_TEMPLATE: "制造模板（工程专属口径不适用）"
};

// 真实原因代码 → 中文说明。只覆盖核心实际会产出的代码；未登记的代码不猜，显示“请求未执行”。
const ENGINEERING_REASON_TEXT = {
  ACCEPTED: "已受理",
  SIGNED: "合同已签署",
  CONTRACT_SIGNED: "合同已签署",
  OPPORTUNITY_NOT_READ: "未实际读到此机会，不能签约",
  UNKNOWN_OPPORTUNITY: "该合同条款不在冻结机会里",
  NOT_AUTHORIZED: "当前岗位没有该权限",
  NOT_CHAIR: "不是章程主席，不能召集",
  WORK_STARTED: "已开工（正耗时）",
  WORK_COMPLETED: "工时结束，工单已完工",
  WORK_NOT_DUE: "工时未到，尚不能完工",
  WORK_STATE_INVALID: "工单状态不允许该操作",
  EXCEEDS_COMPLETED: "超过已完工单位",
  EXCEEDS_CONTRACT_UNITS: "超过合同单位",
  INVOICE_ORDER: "开票顺序不符：须先验收先前阶段",
  NOT_ACCEPTED: "该阶段尚未验收",
  TRANCHE_MISMATCH: "期次与已开票进度不匹配",
  NOT_DUE: "尚未到期",
  INVOICED: "已开票",
  COLLECTED: "已收款",
  OVER_COLLECT: "收款超过未清应收",
  RETENTION_COLLECTED: "质保金已收",
  IMPAIRED: "已计提减值",
  IMPAIR_EXCEEDS_GROSS: "减值超过应收总额",
  WRITTEN_OFF: "已核销",
  WRITE_OFF_EXCEEDS_ALLOWANCE: "核销超过已计提减值",
  RECOVERED: "已回收",
  RECOVERY_EXCEEDS_MEMO: "回收超过备查金额",
  WAIVED: "已按授权豁免",
  WAIVE_EXCEEDS_OBLIGATION: "豁免超过未清义务",
  WAIVER_MISMATCH: "豁免与董事会授权原文不一致",
  WAIVER_AUTHORIZATION_REQUIRED: "缺少董事会豁免授权引用",
  WAIVER_ALREADY_CONSUMED: "该豁免授权已被使用",
  WAIVER_NOT_AUTHORIZED: "董事会尚未签发该豁免",
  TERMINATED: "合同已终止",
  SUPPLIER_PAID: "已支付供应商",
  PAYMENT_EXCEEDS_PAYABLE: "付款超过应付",
  INSUFFICIENT_CASH: "公司现金不足",
  ADVANCE_RECEIVED: "已收到客户预收",
  ADVANCE_EXCEEDS_TERMS: "预收超过合同条款",
  TERMS_NOT_CLOSED: "预收与质保比例之和超过 100%",
  RETENTION_CONDITION_UNSUPPORTED: "不支持附带条件的质保",
  CUSTOMER_REQUESTED: "已向客户提出请求（仅意图，不是付款）",
  UNMATCHED_REQUEST: "客户事件没有匹配的未回答请求",
  RESPONSE_BEFORE_REQUEST: "客户回应时点早于请求",
  NO_DUE_RECEIVABLE: "没有到期未清的债权",
  PAYER_ACCOUNT_MISMATCH: "付款账户不是本公司的客户账户",
  BUDGET_PERIOD_CLOSED: "客户期间预算已关闭",
  CUSTOMER_BUDGET_EXHAUSTED: "客户期间预算不足",
  BUDGET_NOT_SET: "尚无已生效预算",
  BUDGET_EXCEEDED: "超过预算上限",
  BUDGET_BELOW_COMMITTED: "新上限低于已占用金额",
  NOT_ADOPTED: "决议未通过",
  ADJOURNED_NO_QUORUM: "法定人数不足，会议延期",
  VOTING_CLOSED: "表决已截止",
  ALREADY_VOTED: "本席位已投票",
  NOT_ELIGIBLE_VOTER: "本席位无权表决（未到会或已回避）",
  WRONG_TOPIC: "议题不匹配",
  PROPOSAL_EXISTS: "已有提案",
  WAIVER_PROPOSAL_EXISTS: "已有豁免提案",
  MATERIALS_FROZEN: "材料已冻结",
  MATERIALS_CLOSED: "材料已关闭",
  MATERIALS_NOT_FROZEN: "材料尚未冻结",
  MEETING_EXISTS: "会议已存在",
  UNKNOWN_MEETING: "未知会议",
  EMPTY_PROPOSAL: "提案为空",
  DUPLICATE_BUDGET_LINE: "预算行重复",
  INVALID_AMOUNT: "金额不合法",
  INVALID_QUANTITY: "数量不合法",
  INVALID_STATE: "当前状态不允许该操作",
  INVALID_TIMELINE: "时间线不合法",
  INVALID_ATTENDANCE: "参与登记不合法",
  UNKNOWN_SOURCE: "材料来源不存在",
  UNKNOWN_CONTRACT: "没有这份合同",
  UNKNOWN_WORK: "没有这个工单",
  CONTRACT_NOT_ACTIVE: "合同不在履约中",
  ID_REUSED: "标识已被使用",
  COMMAND_CONTENT_CONFLICT: "同一命令的内容不一致",
  REPLAYED: "重复提交，按原结果返回",
  OVERFLOW: "数值溢出",
  ALLOCATION_INVALID: "开局分配不合法",
  STARTED: "已真实开始（BEGIN 屏障通过；不代表签约或到账）",
  REQUEST_PENDING: "同键已有未回答请求，先等回应",
  RECOVERY_REQUESTED: "已记录追索请求（客户回应前不会到账）",
  REJECTED_PAYMENT: "客户未付款（真实回应，金额为 0）",
  NOT_INVOICED: "该期尚未开票",
  NO_WRITTEN_OFF_CLAIM: "没有核销后的备查债权",
  NO_ROOM_FOR_ADVANCE: "合同预收额度已用满",
  INVALID_TRANCHE: "期次不在合同计划里",
  ALREADY_ACCEPTED: "该期已验收",
  ACCEPTANCE_ORDER: "验收顺序不符",
  UNKNOWN_COMPANY: "不属于本档案的工程公司"
};

// 未登记代码：既不标成功也不标失败，只给中性说明并保留原码；真实 accepted 由表格单独显示。
const ENGINEERING_UNKNOWN_REASON_TEXT = "暂无结果说明，请查看原始记录";

function engineeringReason(code) {
  if (code === null || code === undefined || code === "") {
    return ENGINEERING_UNKNOWN_REASON_TEXT;
  }
  const known = ENGINEERING_REASON_TEXT[code];
  return known || ENGINEERING_UNKNOWN_REASON_TEXT;
}

function engineeringReasonKnown(code) {
  return Object.prototype.hasOwnProperty.call(ENGINEERING_REASON_TEXT, code);
}

// 结果列只听真实 accepted，不从原因码反推业务状态。
function engineeringReportVerdict(accepted) {
  return accepted ? "成功" : "拒绝";
}

// BEGIN 屏障报告与 FINISH 业务报告分开显示，未知原因不改变阶段判定。
function engineeringStageLabel(row) {
  if (!row) {
    return "—";
  }
  if (row.kind === "ENGINEERING_BEGIN_V1") {
    return "BEGIN（真实开始）";
  }
  if (typeof row.taskId === "string" && row.taskId.endsWith(":finish")) {
    return "FINISH（业务落账）";
  }
  return "—";
}

function engineeringReportText(accepted, code) {
  if (!engineeringReasonKnown(code)) {
    return ENGINEERING_UNKNOWN_REASON_TEXT;
  }
  return ENGINEERING_REASON_TEXT[code];
}

const ENGINEERING_STATUS_TEXT = {
  ACTIVE: "在任", REVOKED: "已撤销", EXPIRED: "已到期", SCHEDULED: "未开始"
};
const ENGINEERING_KIND_TEXT = {
  SIGN: "签约", START_WORK: "开工", COMPLETE_WORK: "完工", INVOICE: "开票",
  REQUEST_ACCEPTANCE: "请求验收", REQUEST_INVOICE: "请求开票", REQUEST_PAYMENT: "请求付款",
  REQUEST_RETENTION: "请求质保", IMPAIR: "计提减值", WRITE_OFF: "核销", RECOVER: "追索回款",
  TERMINATE: "终止", PAY_SUPPLIER: "付供应商", WAIVE: "执行豁免",
  ADVANCE: "预收", ACCEPT: "验收", REJECT: "拒验", DELAY: "延迟", PAY: "付款",
  RETENTION_PAY: "质保付款", OVERDUE: "逾期",
  CONVENE: "召集", ATTEND: "参与", MATERIAL: "材料", PROPOSE_BUDGET: "预算提案",
  PROPOSE_APPOINTMENT: "任免提案", VOTE: "表决", IMPLEMENT: "实施",
  CONVENE_WAIVER: "召集豁免会", PROPOSE_WAIVER: "豁免提案"
};

function engineeringToolLabel(value) {
  return ENGINEERING_TOOL_LABELS[value] || value || "—";
}

function engineeringTerm(value, table) {
  if (value === null || value === undefined || value === "") return "—";
  return table[value] || value;
}

/** 任务种类：BEGIN 是已知包装种类（字符串兼容），显示为等待中的回执，不当已完成。 */
function engineeringKindLabel(value) {
  if (value === "ENGINEERING_BEGIN_V1") {
    return "开始回执包装（BEGIN，等待落账）";
  }
  return engineeringTerm(value, ENGINEERING_KIND_TEXT);
}

function engineeringScope(value) {
  return ENGINEERING_SCOPE_TEXT[value] || text(value);
}

function wallClock(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) return "—";
  return new Date(number).toLocaleTimeString("zh-CN", { hour12: false });
}

/**
 * 世界毫秒转“运行X天Y小时”的可读时长；只做展示换算，精确世界毫秒仍原样显示，
 * 不改任何时间字段或语义。
 */
function worldSpanText(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) return "";
  const days = Math.floor(number / 86_400_000);
  const hours = Math.floor((number % 86_400_000) / 3_600_000);
  return "运行" + days + "天" + hours + "小时";
}

/** 时点展示：精确世界毫秒 + 可读时长，两者同时给出。 */
function worldTimeText(value) {
  if (value === null || value === undefined || value === "") return "—";
  return value + " 毫秒（" + worldSpanText(value) + "）";
}

function clearEngineeringDom() {
  state.engineering = null;
  state.engineeringCompany = "";
  state.engineeringRequest += 1;
  if ($("engineering-company")) $("engineering-company").replaceChildren();
  for (const id of ["engineering-report", "engineering-seats", "engineering-appointments",
    "engineering-contracts", "engineering-works", "engineering-budgets",
    "engineering-commitments", "engineering-meetings", "engineering-requests",
    "engineering-responses", "engineering-payments", "engineering-waivers",
    "engineering-tasks", "engineering-reports"]) {
    if ($(id)) fillTable(id, [], () => undefined);
  }
  for (const id of ["engineering-cut", "engineering-template", "engineering-report-note",
    "engineering-captured", "engineering-appointments-count", "engineering-contracts-count",
    "engineering-works-count", "engineering-meetings-count", "engineering-requests-count",
    "engineering-responses-count", "engineering-payments-count", "engineering-waivers-count",
    "engineering-tasks-count", "engineering-reports-count"]) {
    if ($(id)) $(id).textContent = "";
  }
  for (const id of ["engineering-sources", "engineering-notes"]) {
    if ($(id)) $(id).replaceChildren();
  }
  if ($("engineering-periodic")) $("engineering-periodic").replaceChildren();
  if ($("engineering-report-filter")) $("engineering-report-filter").value = "ALL";
  document.querySelectorAll('[data-panel="engineering"] .empty').forEach(node => { node.hidden = true; });
  setMessage("engineering-note", "");
  setMessage("engineering-error", "");
}

/** 制造模板没有工程工单/质保/客户池时，空态要写“不适用”，不能写成“缺失”。 */
const ENGINEERING_EMPTY_TEXT = {
  "engineering-works-empty": { engineering: "尚无工单。", manufacturing: "不适用于该制造经营模板。" },
  "engineering-requests-empty": { engineering: "暂无记录。", manufacturing: "不适用于该制造经营模板。" },
  "engineering-responses-empty": { engineering: "暂无记录。", manufacturing: "不适用于该制造经营模板。" },
  "engineering-waivers-empty": { engineering: "尚无豁免记录。", manufacturing: "不适用于该制造经营模板。" }
};

// 数据来源区块用中文名做主信息；技术路径仍保留在数值里（放在可展开细节中）。
const ENGINEERING_SOURCE_LABELS = {
  report: "经营报表",
  contracts: "合同与分期",
  workOrders: "施工工单",
  budgets: "预算上限",
  commitments: "预算占用",
  meetings: "董事会会议",
  requests: "客户请求",
  responses: "客户回应",
  payments: "真实到账",
  waivers: "债权豁免",
  tasks: "待执行任务",
  reports: "操作结果",
  time: "时间口径"
};

function setEngineeringEmptyTexts(template) {
  const manufacturing = template === "MANUFACTURING";
  for (const [id, texts] of Object.entries(ENGINEERING_EMPTY_TEXT)) {
    const node = $(id);
    if (node) node.textContent = manufacturing ? texts.manufacturing : texts.engineering;
  }
}

function fillEngineeringCompanies(companies, selected) {
  const select = $("engineering-company");
  if (!select) return;
  select.replaceChildren();
  for (const company of companies || []) {
    const option = document.createElement("option");
    option.value = company.companyId;
    option.textContent = (company.name || company.companyId) + "（"
      + (company.template === "ENGINEERING" ? "工程" : "制造") + "）";
    select.appendChild(option);
  }
  select.value = selected || "";
}

async function loadEngineering(generation, pageVersion) {
  if (state.mode !== "admin") return;
  const ticket = ++state.engineeringRequest;
  let result;
  try {
    result = await request("POST", "/api/admin/engineering",
      { companyId: state.engineeringCompany || "" });
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "admin" && ticket === state.engineeringRequest) {
      setMessage("engineering-error", failure.message);
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin" || ticket !== state.engineeringRequest) return;
  if (pageVersion !== undefined && pageVersion !== state.pageVersion) return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) {
    setMessage("engineering-error", "读取工程经营失败（" + result.status + "），请稍后重试。");
    return;
  }
  setMessage("engineering-error", "");
  state.engineeringCompany = result.payload.companyId || "";
  state.engineering = result.payload;
  renderEngineering(result.payload);
}

// ---------------------------------------------------------------- 周期治理（公司/董事页共用）

const PERIODIC_REASON_TEXT = {
  OK: "已汇总",
  NO_QUORUM: "完整有效席不足三名，未形成总分"
};
const PERIODIC_AUTH_STATUS_TEXT = {
  PENDING: "未消费（有效期内）",
  CONSUMED: "已消费",
  EXPIRED: "已过期未消费"
};

function periodicReasonText(code) {
  if (!code) return "尚未形成汇总";
  return PERIODIC_REASON_TEXT[code] || "未形成有效汇总";
}

function periodicMissingNames(ballot, row) {
  const unavailable = new Set(((row && row.unavailableDimensions) || [])
    .map(item => item.dimension));
  return (ballot.missingDimensions || []).map(dimension => {
    const name = PERIODIC_DIMENSION_TEXT[dimension] || dimension;
    return unavailable.has(dimension) ? name + "（无合格证据）" : name;
  }).join("、") || "—";
}

function periodicDl(container, pairs) {
  const dl = document.createElement("dl");
  dl.className = "grid";
  for (const [label, value] of pairs) {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = text(value);
    dl.append(dt, dd);
  }
  container.appendChild(dl);
  return dl;
}

function periodicTable(container, caption, headers, rows, emptyText) {
  if (caption) {
    const title = document.createElement("h4");
    title.textContent = caption;
    container.appendChild(title);
  }
  if (!rows.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = emptyText;
    container.appendChild(empty);
    return;
  }
  const wrap = document.createElement("div");
  wrap.className = "table-wrap";
  const table = document.createElement("table");
  const head = document.createElement("thead");
  const headRow = document.createElement("tr");
  for (const label of headers) {
    const th = document.createElement("th");
    th.scope = "col";
    th.textContent = label;
    headRow.appendChild(th);
  }
  head.appendChild(headRow);
  table.appendChild(head);
  const body = document.createElement("tbody");
  for (const values of rows) {
    const tr = document.createElement("tr");
    for (const value of values) cell(tr, value);
    body.appendChild(tr);
  }
  table.appendChild(body);
  wrap.appendChild(table);
  container.appendChild(wrap);
}

function periodicPeople(rows) {
  return (rows || []).map(row => (row.personName || row.personPartyId || row.seatId)).join("、")
    || "—";
}

/** 周期区块：God 与董事/经理共用；没有事实就显示缺项，不补 0、不出假总分。 */
function renderPeriodicBlock(container, periodic, options) {
  if (!container) return;
  container.replaceChildren();
  if (!periodic || !periodic.available) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = periodic && periodic.reason === "NO_PERIODIC_STATE"
      ? "本次运行未启用周期治理（新档才有）；旧档不补样例数据。"
      : "本次运行没有可显示的周期事实。";
    container.appendChild(empty);
    return;
  }
  const personView = !!(options && options.personView);
  const head = document.createElement("p");
  head.className = "hint";
  head.textContent = personView
    ? "周期来自真实采样与董事会动作；本人只看自己的选票与已允许的汇总/进度。"
      + "公开财报正文需本人实际读完该版本后才显示；已遗忘的正文要重新阅读才会再次出现。"
    : "周期来自真实采样与董事会动作；公开正文原样取自已发布版本。"
      + "累计收入/累计成本是开运以来口径，不是单期发生额。";
  container.appendChild(head);
  const rows = (periodic.periods || []).slice().reverse();
  if (!rows.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "尚未到第一个期末，暂无采样与披露记录。";
    container.appendChild(empty);
    return;
  }
  for (const row of rows) {
    const section = document.createElement("section");
    section.className = "periodic-period";
    const title = document.createElement("h4");
    title.textContent = "第" + row.period + "期 · "
      + (row.kind === "QUARTERLY" ? "季度正式考核" : "月度复盘")
      + " · 截至 " + worldTimeText(row.capturedAt);
    section.appendChild(title);
    const meeting = row.meeting || {};
    const drafts = row.drafts || [];
    const latestDraft = row.latestDraft || null;
    const score = row.score || {};
    const publication = row.publication || {};
    const approvals = latestDraft ? latestDraft.approvalCount + "/"
      + latestDraft.requiredApprovals : "—";
    const summary = [
      ["截至时点", worldTimeText(row.capturedAt)],
      ["披露截止", row.deadline ? worldTimeText(row.deadline) : "—"],
      ["考核会议", meeting.meetingId ? (meeting.meetingId + " · " + meeting.status
        + " · " + worldTimeText(meeting.opensAt) + " — " + worldTimeText(meeting.closesAt)) : "—"],
      ["真实出席", periodicPeople(meeting.attendees)],
      ["回避", periodicPeople(meeting.recused)],
      ["本期材料", row.materialDocId + " v" + row.materialVersion
        + (options && options.showReadState ? " · 本人已读完：" + (row.myReadMaterial ? "是" : "否")
          + (row.myReadMaterial === true && row.myRecallEligible === false
            ? " · 当前不可回忆（可重读）" : "") : "")],
      ["草稿版本", latestDraft ? ("v" + latestDraft.version + " · 由 " + (latestDraft.preparedByName || latestDraft.preparedBy)
        + " 于 " + worldTimeText(latestDraft.preparedAt) + " 准备") : "尚未准备"],
      ["3席批准进度", approvals],
      ["评分汇总", (score.closed ? periodicReasonText(score.reason) : "尚未关会汇总")
        + (score.closed && score.total !== null && score.total !== undefined ? " · 总分 " + score.total : " · 无总分")],
      ["公开版本", publication.released
        ? (publication.latestVersion
          ? ("v" + publication.latestVersion + " · " + worldTimeText(publication.latestPublishedAt) + " 发布")
          : ("已发布 · 版本号未在真实资料中定位 · " + worldTimeText(publication.latestPublishedAt)))
        : "尚未发布"],
      ["迟报事实", publication.late ? "已迟报（保留原发布时间）" : "未迟报"],
      ["错过截止", publication.missed
        ? ("已记录 MISSED · " + worldTimeText(publication.missedAt)) : "未记录"]
    ];
    periodicDl(section, summary);

    const unavailable = row.unavailableDimensions || [];
    const dimensionStates = row.dimensionStates || [];
    const dimensionName = (dimension) => PERIODIC_DIMENSION_TEXT[dimension] || dimension;
    const addHint = (text) => {
      const note = document.createElement("p");
      note.className = "hint";
      note.textContent = text;
      section.appendChild(note);
    };
    if (dimensionStates.length) {
      const missing = dimensionStates.filter(item => item.state === "NO_ELIGIBLE_EVIDENCE"
        || item.state === "DIMENSION_NOT_AVAILABLE");
      const awaiting = dimensionStates.filter(item => item.state === "AWAITING_SCORE");
      if (missing.length) {
        addHint("本期无合格证据（缺项，不补 0）：" + missing.map(item =>
          dimensionName(item.dimension)
          + (item.state === "DIMENSION_NOT_AVAILABLE" ? "（无合格机制）" : "（无合格证据）")).join("、"));
      }
      if (awaiting.length) {
        addHint((row.dimensionScope === "ALL_SEATS"
          ? "本期有合格证据、还没有人评分（不是缺项）："
          : "本期有合格证据、本人尚未就这些维度评分（不是缺项）：")
          + awaiting.map(item => dimensionName(item.dimension)).join("、"));
      }
    } else if (unavailable.length) {
      addHint("本期缺项（不补 0）：" + unavailable.map(item =>
        dimensionName(item.dimension)
        + (item.reason === "DIMENSION_NOT_AVAILABLE" ? "（无合格机制）" : "（无合格证据）")).join("、"));
    }

    periodicTable(section, "草稿与批准（每版单独保留）",
      ["草稿", "草稿版本", "准备人", "准备时间", "批准数", "3席门槛"],
      drafts.map(draft => [draft.draftId, "v" + draft.version,
        draft.preparedByName || draft.preparedBy, worldTimeText(draft.preparedAt),
        draft.approvalCount, draft.requiredApprovals]),
      "尚未准备草稿。");

    const ballots = score.ballots || [];
    periodicTable(section, "各席评分与缺维（保留原始意见）",
      ["席位", "评分人", "提交时间", "状态", "缺维", "五维分", "理由"],
      ballots.map(ballot => [ballot.seatId, ballot.personName || ballot.personPartyId,
        ballot.submittedAt, ballot.status, periodicMissingNames(ballot, row),
        (ballot.metrics || []).map(metric => (PERIODIC_DIMENSION_TEXT[metric.dimension] || metric.dimension)
          + " " + metric.score).join("；") || "—", ballot.rationale || "—"]),
      "尚无有效评分。");
    if (score.closed) {
      const aggregate = document.createElement("p");
      aggregate.className = "hint";
      aggregate.textContent = "汇总结果：" + periodicReasonText(score.reason)
        + " · 参与聚合完整席 " + score.seatCount + " 席"
        + (score.total === null || score.total === undefined ? " · 无总分（缺维不补）" : " · 总分 " + score.total);
      section.appendChild(aggregate);
    } else {
      const open = document.createElement("p");
      open.className = "hint";
      open.textContent = personView
        ? (ballots.length ? "本人已提交本席评分，等待真实关会汇总。" : "本人尚未提交评分。")
        : (ballots.length ? "尚未汇总：已收 " + ballots.length + " 席，等待真实关会。"
          : "尚未汇总：还没有有效评分。");
      section.appendChild(open);
    }

    periodicTable(section, "披露授权（期限与消费）",
      ["授权", "草稿版本", "指定发布人", "签发", "失效", "消费", "状态"],
      (row.authorizations || []).map(authorization => [authorization.authorizationId,
        authorization.draftVersion ? "v" + authorization.draftVersion : "—",
        (authorization.publisherName || authorization.publisherPersonId || "—")
          + (authorization.mine === true ? "（本人可执行）" : ""),
        authorization.issuedAt || "—", authorization.expiresAt || "—",
        authorization.consumedAt === undefined ? "—"
          : (authorization.consumedAt === "0" ? "未消费" : authorization.consumedAt),
        PERIODIC_AUTH_STATUS_TEXT[authorization.status] || authorization.status]),
      "尚无披露授权。");

    const releases = row.releases || [];
    const releaseVersionText = (release) => release.publicVersion
      ? "v" + release.publicVersion
      : (release.versionResolved === false ? "未定位" : "—");
    const releaseHeaders = ["公开版本", "草稿版本", "发布时间", "发布人", "迟报"];
    if (personView) releaseHeaders.push("本人阅读");
    periodicTable(section, "公开版本与更正链", releaseHeaders,
      releases.map(release => {
        const cells = [releaseVersionText(release),
          release.draftVersion ? "v" + release.draftVersion : "—",
          worldTimeText(release.publishedAt), release.publisherName || release.publisherPersonId,
          release.late ? "是（保留 MISSED）" : "否"];
        if (personView) {
          cells.push(release.recallable ? "已读 · 当前可回忆"
            : (release.forgotten ? "已读过 · 当前不可回忆（需重读）" : "未读"));
        }
        return cells;
      }),
      "尚未发布公开财报。");
    if (releases.length > 1 || drafts.length > 1) {
      const chain = document.createElement("p");
      chain.className = "hint";
      chain.textContent = "更正链：草稿 " + drafts.map(draft => "v" + draft.version).join(" → ")
        + (releases.length ? "；公开 " + releases.map(releaseVersionText).join(" → ") : "")
        + "。旧版本、旧批准与旧公开记录保留；未发表的草稿修订不增加公开版本。";
      section.appendChild(chain);
    }
    const latest = releases.length ? releases[releases.length - 1] : null;
    if (latest && latest.body) {
      const details = document.createElement("details");
      details.className = "periodic-body";
      const summaryNode = document.createElement("summary");
      summaryNode.textContent = "公开正文（后端原文 · 公开 " + releaseVersionText(latest) + "）";
      const body = document.createElement("pre");
      body.textContent = latest.body;
      details.append(summaryNode, body);
      section.appendChild(details);
    } else if (latest && personView) {
      const note = document.createElement("p");
      note.className = "hint";
      if (latest.versionResolved === false) {
        note.textContent = "已发布（发布时间 " + latest.publishedAt + " 毫秒）；"
          + "后端未在真实资料中定位到该次发布的文档版本，正文与阅读入口暂不提供。";
      } else if (latest.forgotten) {
        note.textContent = "已发布公开 " + releaseVersionText(latest) + "；本人过去实际读过该版本，"
          + "但按既有回忆口径当前已不可回忆。重新阅读该公开版本后才会再次显示正文。";
      } else {
        note.textContent = "已发布公开 " + releaseVersionText(latest)
          + (latest.title ? "（" + latest.title + "）" : "")
          + "；正文需本人实际读完该公开版本后才显示。";
      }
      section.appendChild(note);
      if (latest.publicDocId && latest.publicVersion) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "quiet";
        button.textContent = (latest.forgotten ? "重新阅读公开财报 " : "阅读公开财报 ")
          + releaseVersionText(latest);
        button.addEventListener("click", () => guard(schedulePeriodicReading(
          periodic.companyPartyId, row.period, latest.publicDocId, latest.publicVersion)));
        section.appendChild(button);
      }
    }

    const pending = row.pendingActions || [];
    if (pending.length) {
      periodicTable(section, "已排程 / 待执行（不是已完成）",
        ["动作", "人物", "到期时点", "状态"],
        pending.map(action => [engineeringToolLabel(action.action === "ATTEND" ? "periodic.attend"
          : "periodic." + String(action.action || "").toLowerCase()), action.personPartyId,
          action.dueTimeMillis, "已排程（待人执行）"]),
        "没有待执行动作。");
    }
    container.appendChild(section);
  }
}

function renderEngineering(view) {
  $("engineering-cut").textContent = cutText(view, (view.companyName || view.companyId || "")
    + " · " + (view.template === "ENGINEERING" ? "工程模板" : "制造模板"));
  $("engineering-captured").textContent = "读取于 " + wallClock(view.capturedAtMillis);
  $("engineering-template").textContent = view.template === "ENGINEERING" ? "工程模板" : "制造模板";
  setMessage("engineering-note", view.note || "");
  setEngineeringEmptyTexts(view.template);
  fillEngineeringCompanies(view.companies, view.companyId);
  renderPeriodicBlock($("engineering-periodic"), view.periodic, { showReadState: false });

  const report = view.report || {};
  const reportRows = [];
  for (const [key, label] of ENGINEERING_REPORT_LABELS) {
    if (!(key in report)) continue;
    const raw = report[key];
    let value;
    if (key.endsWith("Scope")) value = engineeringScope(raw);
    else if (key === "templateNote") value = engineeringScope(raw);
    else if (typeof raw === "boolean") value = raw ? "成立" : "不成立";
    else value = text(raw);
    reportRows.push({ label, value });
  }
  fillTable("engineering-report", reportRows, (tr, row) => {
    cell(tr, row.label);
    cell(tr, row.value, "num");
  });
  $("engineering-report-note").textContent = view.enabled
    ? "同一确认记录 " + view.position + " · 世界时点 " + view.timeMillis + " 毫秒：现金、应收、应付、收入各占一列，不能相加当收入。"
    : (view.note || "");

  fillTable("engineering-seats", (view.seats || {}).rows, (tr, row) => {
    cell(tr, row.seatId);
    cell(tr, row.partyId);
    cell(tr, row.partyName);
    cell(tr, row.chair ? "主席" : "");
  });
  fillSection("engineering-seats-empty", null, view.seats);

  fillTable("engineering-appointments", (view.appointments || {}).rows, (tr, row) => {
    cell(tr, row.appointmentId);
    cell(tr, row.personName || row.personPartyId);
    cell(tr, row.role);
    cell(tr, row.startsAt, "num");
    cell(tr, row.endsAt, "num");
    cell(tr, row.revokedAt, "num");
    cell(tr, engineeringTerm(row.status, ENGINEERING_STATUS_TEXT));
  });
  fillSection("engineering-appointments-empty", "engineering-appointments-count", view.appointments);

  fillTable("engineering-contracts", (view.contracts || {}).rows, (tr, row) => {
    cell(tr, row.contractId);
    cell(tr, row.unitPrice, "num");
    cell(tr, row.totalUnits, "num");
    cell(tr, row.completedUnits, "num");
    cell(tr, row.acceptedUnits, "num");
    cell(tr, row.invoicedTranches, "num");
    cell(tr, row.advanceReceived, "num");
    cell(tr, row.retentionWithheld, "num");
    cell(tr, row.revenue, "num");
    cell(tr, row.cogs, "num");
    cell(tr, row.arCurrent, "num");
    cell(tr, row.arRetention, "num");
    cell(tr, row.allowance, "num");
    cell(tr, row.memo, "num");
    cell(tr, row.status);
  });
  fillSection("engineering-contracts-empty", "engineering-contracts-count", view.contracts);

  fillTable("engineering-works", (view.workOrders || {}).rows, (tr, row) => {
    cell(tr, row.workId);
    cell(tr, row.contractId);
    cell(tr, row.units, "num");
    cell(tr, row.startedAt, "num");
    cell(tr, row.completesAt, "num");
    cell(tr, row.totalCost, "num");
    cell(tr, row.laborCost, "num");
    cell(tr, row.remainingCost, "num");
    cell(tr, row.acceptedUnits, "num");
    cell(tr, row.status);
  });
  fillSection("engineering-works-empty", "engineering-works-count", view.workOrders);

  fillTable("engineering-budgets", (view.budgets || {}).rows, (tr, row) => {
    cell(tr, row.period);
    cell(tr, row.category);
    cell(tr, row.cap, "num");
  });
  fillSection("engineering-budgets-empty", null, view.budgets);

  fillTable("engineering-commitments", (view.commitments || {}).rows, (tr, row) => {
    cell(tr, row.commitmentId);
    cell(tr, row.period);
    cell(tr, row.category);
    cell(tr, row.reserved, "num");
    cell(tr, row.spent, "num");
    cell(tr, row.released, "num");
    cell(tr, row.outstanding, "num");
    cell(tr, row.status);
  });
  fillSection("engineering-commitments-empty", null, view.commitments);

  fillTable("engineering-meetings", (view.meetings || {}).rows, (tr, row) => {
    cell(tr, row.meetingId);
    cell(tr, engineeringTerm(row.topic, ENGINEERING_KIND_TEXT));
    cell(tr, row.status);
    cell(tr, row.materialDeadline, "num");
    cell(tr, row.opensAt, "num");
    cell(tr, row.closesAt, "num");
    cell(tr, row.attendees);
    cell(tr, row.recused);
    cell(tr, row.ballots);
    cell(tr, row.proposedLines);
    cell(tr, row.failureReason);
  });
  fillSection("engineering-meetings-empty", "engineering-meetings-count", view.meetings);

  fillTable("engineering-requests", (view.requests || {}).rows, (tr, row) => {
    cell(tr, row.requestId);
    cell(tr, row.contractId);
    cell(tr, row.trancheIndex, "num");
    cell(tr, engineeringTerm(row.kind, ENGINEERING_KIND_TEXT));
    cell(tr, row.atMillis, "num");
    cell(tr, row.personPartyId);
    cell(tr, row.answered ? "已回应" : "未回应");
    cell(tr, row.answeredByEventId);
  });
  fillSection("engineering-requests-empty", "engineering-requests-count", view.requests);

  fillTable("engineering-responses", (view.responses || {}).rows, (tr, row) => {
    cell(tr, row.eventId);
    cell(tr, row.contractId);
    cell(tr, row.trancheIndex, "num");
    cell(tr, row.outcome);
    cell(tr, row.requestId);
    cell(tr, row.atMillis, "num");
    cell(tr, row.amount, "num");
    cell(tr, row.currentOutstanding, "num");
    cell(tr, row.retentionOutstanding, "num");
    cell(tr, row.reason);
  });
  fillSection("engineering-responses-empty", "engineering-responses-count", view.responses);

  fillTable("engineering-payments", (view.payments || {}).rows, (tr, row) => {
    cell(tr, row.eventId);
    cell(tr, row.poolAccountId);
    cell(tr, row.period, "num");
    cell(tr, row.contractId);
    cell(tr, row.trancheIndex, "num");
    cell(tr, row.kind);
    cell(tr, row.amount, "num");
    cell(tr, row.atMillis, "num");
  });
  fillSection("engineering-payments-empty", "engineering-payments-count", view.payments);

  fillTable("engineering-waivers", (view.waivers || {}).rows, (tr, row) => {
    cell(tr, row.waiverId);
    cell(tr, row.contractId);
    cell(tr, row.customerPartyId);
    cell(tr, row.amount, "num");
    cell(tr, row.purpose);
    cell(tr, row.authorized ? "已授权" : "未授权");
    cell(tr, row.consumed ? "已执行" : "未执行");
    cell(tr, row.authorizationRef);
    cell(tr, row.meetingId);
  });
  fillSection("engineering-waivers-empty", "engineering-waivers-count", view.waivers);

  fillTable("engineering-tasks", (view.tasks || {}).rows, (tr, row) => {
    cell(tr, row.taskId);
    cell(tr, row.dueTimeMillis, "num");
    cell(tr, row.phase);
    cell(tr, engineeringKindLabel(row.kind));
    cell(tr, row.wrappedKind ? engineeringKindLabel(row.wrappedKind) : "—");
    cell(tr, row.wrapperDueMillis, "num");
    cell(tr, engineeringTerm(row.tool, ENGINEERING_KIND_TEXT));
    cell(tr, row.state === "SCHEDULED" ? "已排程（待到期）" : text(row.state));
  });
  fillSection("engineering-tasks-empty", "engineering-tasks-count", view.tasks);

  renderEngineeringReports(view);
  fillDl("engineering-sources", Object.entries(view.sources || {})
    .map(([key, value]) => [ENGINEERING_SOURCE_LABELS[key] || key, value]));
  fillNotes("engineering-notes", view.notes);
}

function renderEngineeringReports(view) {
  const filter = $("engineering-report-filter") ? $("engineering-report-filter").value : "ALL";
  const all = ((view.reports || {}).rows) || [];
  const rows = all.filter(row => filter === "ALL"
    || (filter === "ACCEPTED" ? row.accepted : !row.accepted));
  fillTable("engineering-reports", rows, (tr, row) => {
    cell(tr, row.source);
    cell(tr, row.taskId);
    cell(tr, engineeringKindLabel(row.kind));
    cell(tr, engineeringStageLabel(row));
    cell(tr, engineeringReportVerdict(row.accepted));
    const textCell = document.createElement("td");
    textCell.textContent = engineeringReportText(row.accepted, row.reason);
    tr.appendChild(textCell);
    const codeCell = document.createElement("td");
    const details = document.createElement("details");
    const summary = document.createElement("summary");
    summary.textContent = "原码";
    const code = document.createElement("code");
    code.textContent = text(row.reason);
    details.append(summary, code);
    codeCell.appendChild(details);
    tr.appendChild(codeCell);
    cell(tr, row.subjectId);
    cell(tr, row.timeMillis, "num");
  });
  const total = (view.reports || {}).total || 0;
  $("engineering-reports-count").textContent = total === 0 ? "" : ("显示 " + rows.length + " / 共 " + total + " 条");
  $("engineering-reports-empty").hidden = total !== 0;
}

// ---------------------------------------------------------------- 个人视角：工程经营

function clearPersonalEngineering() {
  state.personEngineering = null;
  state.engineeringFieldIds = [];
  if ($("person-engineering-card")) $("person-engineering-card").hidden = true;
  if ($("person-engineering-companies")) $("person-engineering-companies").replaceChildren();
  if ($("person-engineering-note")) $("person-engineering-note").textContent = "";
  if ($("eng-company")) $("eng-company").replaceChildren();
  if ($("eng-tool")) $("eng-tool").replaceChildren();
  if ($("eng-fields")) $("eng-fields").replaceChildren();
  if ($("eng-due")) $("eng-due").value = "";
  if ($("eng-tool-note")) $("eng-tool-note").textContent = "";
  if ($("eng-fields-note")) $("eng-fields-note").textContent = "";
  if ($("person-engineering-notes")) $("person-engineering-notes").replaceChildren();
  setMessage("engineering-action-result", "");
  setMessage("engineering-action-error", "");
}

function dlPairs(target, pairs) {
  for (const [key, value] of pairs) {
    const dt = document.createElement("dt");
    dt.textContent = key;
    const dd = document.createElement("dd");
    dd.textContent = text(value);
    target.append(dt, dd);
  }
}

function engineeringTable(title, headers, rows) {
  const wrap = document.createElement("div");
  wrap.className = "table-wrap";
  if (title) {
    const caption = document.createElement("p");
    caption.className = "hint";
    caption.textContent = title;
    wrap.appendChild(caption);
  }
  const table = document.createElement("table");
  const head = document.createElement("thead");
  const headRow = document.createElement("tr");
  for (const label of headers) {
    const th = document.createElement("th");
    th.scope = "col";
    th.textContent = label;
    headRow.appendChild(th);
  }
  head.appendChild(headRow);
  table.appendChild(head);
  const body = document.createElement("tbody");
  for (const row of rows) {
    const tr = document.createElement("tr");
    for (const value of row) cell(tr, value);
    body.appendChild(tr);
  }
  table.appendChild(body);
  wrap.appendChild(table);
  if (!rows.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "暂无记录。";
    wrap.appendChild(empty);
  }
  return wrap;
}

function fillPersonalEngineeringCompanies(companies) {
  const container = $("person-engineering-companies");
  container.replaceChildren();
  if (!companies.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "本人在当前时点没有工程公司的经理任职或董事席位。";
    container.appendChild(empty);
    return;
  }
  for (const company of companies) {
    const card = document.createElement("section");
    card.className = "card eng-company";
    const head = document.createElement("h3");
    head.textContent = (company.companyName || company.companyId) + " · " + company.companyId;
    card.appendChild(head);
    const roleBits = [];
    if (company.manager) roleBits.push("现任经理");
    if (company.seatId) roleBits.push("董事席位 " + company.seatId + (company.chair ? "（主席）" : ""));
    const role = document.createElement("p");
    role.className = "hint";
    role.textContent = roleBits.length ? roleBits.join(" · ") : "当前时点没有可见岗位内容";
    card.appendChild(role);
    for (const line of company.notes || []) {
      if (!line) continue;
      const note = document.createElement("p");
      note.className = "hint";
      note.textContent = line;
      card.appendChild(note);
    }
    if (company.managerVacant === true) {
      const vacant = document.createElement("p");
      vacant.className = "hint";
      vacant.textContent = "经理空缺：需要董事会任命后才能准备周期财报（页面不自动任命，也不放宽任免规则）。";
      card.appendChild(vacant);
    }
    if (company.governanceDocId) {
      // 本人可见的本公司公开治理说明：标题/文档/版本来自本人卡片，正文按真实阅读+回忆门控。
      const noteHead = document.createElement("h4");
      noteHead.textContent = "本公司治理说明（公开模拟说明）";
      card.appendChild(noteHead);
      const noteRead = company.governanceDocRead === true;
      const noteRecallable = company.governanceDocRecallable === true;
      const noteDl = document.createElement("dl");
      noteDl.className = "grid";
      dlPairs(noteDl, [
        ["标题", company.governanceDocTitle || company.governanceDocId],
        ["文档 / 版本", company.governanceDocId + " v" + company.governanceDocVersion],
        ["本人阅读", noteRead
          ? (noteRecallable ? "已读 · 当前可回忆" : "已读过 · 当前不可回忆（需重读）")
          : "未读"]
      ]);
      card.appendChild(noteDl);
      if (!noteRecallable) {
        const readButton = document.createElement("button");
        readButton.type = "button";
        readButton.className = "quiet";
        readButton.textContent = (noteRead ? "重新阅读治理说明 v" : "开始阅读治理说明 v")
          + company.governanceDocVersion;
        readButton.addEventListener("click", () => guard(scheduleDocumentReading(
          company.companyId, company.governanceDocId, company.governanceDocVersion,
          company.governanceDocTitle || "本公司治理说明")));
        card.appendChild(readButton);
      }
      if (noteRecallable && company.governanceDocBody) {
        const details = document.createElement("details");
        details.className = "periodic-body";
        const summary = document.createElement("summary");
        summary.textContent = "治理说明正文（后端原文 v" + company.governanceDocVersion + "）";
        const pre = document.createElement("pre");
        pre.textContent = company.governanceDocBody;
        details.append(summary, pre);
        card.appendChild(details);
      } else {
        const noteHint = document.createElement("p");
        noteHint.className = "hint";
        noteHint.textContent = noteRead
          ? "已读过但当前不可回忆：重新阅读并等世界推进到阅读完成后，才会再次显示正文。"
          : "正文需本人实际读完并仍可回忆才显示；排程后要等世界推进到阅读完成。";
        card.appendChild(noteHint);
      }
    }
    const facts = document.createElement("dl");
    facts.className = "grid";
    const factPairs = [];
    if (company.template !== "MANUFACTURING") {
      factPairs.push(["公司现金", company.companyCash], ["应收当期", company.arCurrent],
        ["应付供应商（汇总）", company.payable],
        ["已签合同", (company.contracts || []).join("、") || "—"]);
    } else {
      const note = document.createElement("p");
      note.className = "hint";
      note.textContent = "制造经营明细与金额在公司经营与治理页；此卡只列本人岗位与周期治理。";
      card.appendChild(note);
    }
    factPairs.push(["本岗位工具", (company.tools || []).map(engineeringToolLabel).join("、") || "—"],
      ["董事席位/法定人数", company.seatId ? (company.seatId + " · " + (company.seatCount || "—")
        + " 席 · 法定 " + (company.quorum || "—")) : "—"]);
    dlPairs(facts, factPairs);
    card.appendChild(facts);

    const customer = company.customer;
    if (customer) {
      const title = document.createElement("h4");
      title.textContent = "客户请求与回应";
      card.appendChild(title);
      const period = document.createElement("dl");
      period.className = "grid";
      dlPairs(period, [
        ["应收当期", customer.arCurrent],
        ["应收质保", customer.arRetention],
        ["备查", customer.memo],
        ["客户义务", customer.obligation]
      ]);
      card.appendChild(period);
      const budgetNote = document.createElement("p");
      budgetNote.className = "hint";
      budgetNote.textContent = "客户内部期间、预算余量、闭期状态与共享池账户本人未获知："
        + "不显示、不补 0；本页只给本公司合同的请求、客户回应与本公司实际到账。";
      card.appendChild(budgetNote);
      card.appendChild(engineeringTable("客户请求（意图，不等于已付款）",
        ["请求", "合同", "期次", "种类", "提出时点", "已回应"],
        (customer.requests || []).map(row => [row.requestId, row.contractId, row.trancheIndex,
          engineeringTerm(row.kind, ENGINEERING_KIND_TEXT), row.atMillis, row.answered ? "已回应" : "未回应"])));
      card.appendChild(engineeringTable("客户回应（外部事实，不等于到账）",
        ["事件", "合同", "期次", "结果", "金额", "当期未清", "质保未清", "原因"],
        (customer.responses || []).map(row => [row.eventId, row.contractId, row.trancheIndex,
          row.outcome, row.amount, row.currentOutstanding, row.retentionOutstanding, row.reason])));
      card.appendChild(engineeringTable("本公司实际到账（真实付款，不含别家公司用款）",
        ["事件", "合同", "期次", "种类", "金额", "时点"],
        (customer.payments || []).map(row => [row.eventId, row.contractId,
          row.trancheIndex, row.kind, row.amount, row.atMillis])));
    }

    if (company.waivers && company.waivers.length) {
      card.appendChild(engineeringTable("具体债权豁免（授权≠已执行）",
        ["豁免", "合同", "金额", "用途", "已授权", "已执行", "授权引用"],
        company.waivers.map(row => [row.waiverId, row.contractId, row.amount, row.purpose,
          row.authorized ? "已授权" : "未授权", row.consumed ? "已执行" : "未执行",
          company.manager ? (row.authorizationRef || "—") : "（董事不持有执行引用）"])));
    }

    if (company.seatId) {
      card.appendChild(engineeringTable("我参与或召集的会议（他人选票只给计数）",
        ["会议", "议题", "状态", "我的角色", "我的票", "计数", "材料", "预算请求", "结果"],
        (company.meetings || []).map(row => [row.meetingId,
          engineeringTerm(row.topic, ENGINEERING_KIND_TEXT), row.status,
          row.role === "CONVENER" ? "召集" : row.role === "RECUSED" ? "回避" : "到会",
          row.myVote || "未投", row.tally, row.materials, row.proposedLines, row.failureReason])));
    }

    const periodicHead = document.createElement("h4");
    periodicHead.textContent = "本公司周期治理（截至、评分与披露）";
    card.appendChild(periodicHead);
    const readBox = document.createElement("div");
    readBox.className = "periodic-read";
    let readButtons = 0;
    for (const row of (company.periodic && company.periodic.periods) || []) {
      if (!company.seatId) continue;
      // 历史已读（myReadMaterial）与当前可回忆（myRecallEligible）分开看：遗忘后仍给重读入口。
      const forgotten = row.myReadMaterial === true && row.myRecallEligible === false;
      if (row.myReadMaterial === true && !forgotten) continue;
      const draft = row.latestDraft;
      if (!draft) continue;
      const button = document.createElement("button");
      button.type = "button";
      button.className = "quiet";
      button.textContent = (forgotten ? "重新阅读第" : "阅读第") + row.period + "期材料（v"
        + draft.version + "）";
      button.addEventListener("click", () => guard(schedulePeriodicReading(
        company.companyId, row.period, draft.docId, draft.version)));
      readBox.appendChild(button);
      readButtons += 1;
    }
    if (readButtons) {
      const hint = document.createElement("p");
      hint.className = "hint";
      hint.textContent = "评分与批准前必须实际读完本期材料；排程后要等世界推进到阅读完成，"
        + "未读完就提交会被真实拒绝并保留原因。";
      readBox.appendChild(hint);
      card.appendChild(readBox);
    } else if (company.seatId) {
      const hint = document.createElement("p");
      hint.className = "hint";
      hint.textContent = "没有待阅读的本期材料：草稿尚未准备，或本人已实际读完。";
      card.appendChild(hint);
    }
    const periodicBox = document.createElement("div");
    renderPeriodicBlock(periodicBox, company.periodic, { showReadState: true, personView: true });
    card.appendChild(periodicBox);
    container.appendChild(card);
  }
}

function fillEngineeringSelectors(companies) {
  const companySelect = $("eng-company");
  if (!companySelect) return;
  companySelect.replaceChildren();
  for (const company of companies) {
    const option = document.createElement("option");
    option.value = company.companyId;
    option.textContent = company.companyName || company.companyId;
    companySelect.appendChild(option);
  }
  refreshEngineeringTools();
}

/** 只列出本公司岗位真实工具；主席额外得到「召集豁免会议」（服务端仍复核主席+召集权）。 */
function engineeringToolsOf(company) {
  const tools = (company && company.tools) || [];
  const list = tools.slice();
  // 豁免召集只存在于工程公司（有工程合同）；制造章程公司由后端不再下发该工具。
  if (company && company.template !== "MANUFACTURING" && company.chair
      && list.includes("board.convene")
      && !list.includes("board.conveneWaiver")) {
    list.push("board.conveneWaiver");
  }
  return list;
}

function refreshEngineeringTools() {
  const companies = (state.personEngineering && state.personEngineering.companies) || [];
  const companySelect = $("eng-company");
  const toolSelect = $("eng-tool");
  if (!companySelect || !toolSelect) return;
  const selected = companies.find(item => item.companyId === companySelect.value);
  const previous = toolSelect.value;
  toolSelect.replaceChildren();
  for (const tool of engineeringToolsOf(selected)) {
    const option = document.createElement("option");
    option.value = tool;
    option.textContent = engineeringToolLabel(tool);
    toolSelect.appendChild(option);
  }
  if (previous && engineeringToolsOf(selected).includes(previous)) {
    toolSelect.value = previous;
  }
  renderEngineeringFields(toolSelect.value);
}

function engineeringWorldNow() {
  const view = state.personEngineering || {};
  const now = Number(view.timeMillis);
  return Number.isFinite(now) && now >= 0 ? now : null;
}

/** 只给时间类字段合理默认值；会议/材料/任职/授权标识一律留空，绝不代造。 */
function engineeringToolDefaults(tool, now) {
  const defaults = {};
  if (now === null) return defaults;
  if (tool === "board.convene" || tool === "board.conveneWaiver"
      || tool === "board.conveneAppointment") {
    defaults.materialDeadline = String(now + 60_000);
    defaults.opensAt = String(now + 120_000);
    defaults.closesAt = String(now + 180_000);
  }
  if (tool === "board.addMaterial") {
    // 默认引用本人卡片上的本公司公开治理说明（真实 docId/版本）；仍可改选其它真实资料。
    defaults.docVersion = "1";
    const card = currentPersonCompany();
    if (card && card.governanceDocId) {
      defaults.docId = String(card.governanceDocId);
      defaults.docVersion = String(card.governanceDocVersion || "1");
    }
  }
  if (tool === "board.proposeBudget") defaults.period = "0";
  if (tool === "engineering.requestAcceptance" || tool === "engineering.requestPayment"
      || tool === "engineering.requestRetention") {
    defaults.trancheIndex = "0";
  }
  if (tool === "board.proposeAppointment") {
    defaults.appointRole = "COMPANY_MANAGER";
    defaults.effectiveAt = String(now + 60_000);
    defaults.endsAt = String(now + 2_592_000_000);
  }
  if (tool === "periodic.authorize") {
    defaults.expiresAt = String(now + 86_400_000);
  }
  return defaults;
}

/** 按所选工具重建字段：上一个工具的旧值随容器一起清掉，不会混进新请求。 */
// 周期五维与中文名：与后端冻结 Dimension 顺序一致；缺维照实显示，不自造分数。
const PERIODIC_DIMENSIONS = [
  ["OPERATING_COMMITMENT", "经营兑现"],
  ["CASH_SOLVENCY", "现金偿债"],
  ["RISK", "风险"],
  ["DISCLOSURE", "披露"],
  ["LONG_TERM_INVESTMENT", "长期投入"]
];
const PERIODIC_DIMENSION_TEXT = Object.fromEntries(PERIODIC_DIMENSIONS);

function selectedEngineeringCompany() {
  return $("eng-company") ? $("eng-company").value : "";
}

function currentPeriodicCard() {
  const companies = (state.personEngineering && state.personEngineering.companies) || [];
  const card = companies.find(item => item.companyId === selectedEngineeringCompany());
  return card && card.periodic && card.periodic.available ? card.periodic : null;
}

/** 当前选择的人物公司卡片（标题/治理说明/工具都从它取，不跨公司复用）。 */
function currentPersonCompany() {
  const companies = (state.personEngineering && state.personEngineering.companies) || [];
  return companies.find(item => item.companyId === selectedEngineeringCompany()) || null;
}

function periodicDefaultPeriod(card) {
  const rows = (card && card.periods) || [];
  return rows.length ? String(rows[rows.length - 1].period) : "";
}

function periodicRowOf(card, period) {
  const rows = (card && card.periods) || [];
  return rows.find(row => String(row.period) === String(period)) || null;
}

function periodicDraftsOf(card, period) {
  const row = periodicRowOf(card, period);
  return (row && row.drafts) || [];
}

function appendOption(select, value, label, dataset) {
  const node = document.createElement("option");
  node.value = value;
  node.textContent = label;
  if (dataset) node.dataset = Object.assign({}, node.dataset || {}, dataset);
  select.appendChild(node);
  return node;
}

/** 周期下拉只从本角色可见的真实公司投影里取；没有可选项时保留空占位。 */
function fillPeriodicOptions(control, source, period, placeholder) {
  control.replaceChildren();
  const card = currentPeriodicCard();
  if (placeholder !== undefined) appendOption(control, "", placeholder);
  if (!card) return;
  if (source === "periods") {
    for (const row of card.periods || []) {
      appendOption(control, String(row.period),
        "第" + row.period + "期 · " + (row.kind === "QUARTERLY" ? "季度" : "月度"));
    }
  } else if (source === "drafts") {
    for (const draft of periodicDraftsOf(card, period)) {
      appendOption(control, draft.draftId,
        "第" + period + "期 · 草稿 v" + draft.version + " · " + (draft.preparedByName || draft.preparedBy),
        { version: String(draft.version) });
    }
  } else if (source === "publishers") {
    for (const seat of card.seats || []) {
      appendOption(control, seat.personPartyId,
        (seat.personName || seat.personPartyId) + "（" + seat.seatId + "）");
    }
  } else if (source === "authorizations") {
    const row = periodicRowOf(card, period);
    for (const authorization of (row && row.authorizations) || []) {
      if (authorization.status !== "PENDING") continue;
      // 经理也能看到本公司的未消费授权；只有指定发布人本人的授权能被执行。
      if (authorization.mine !== true) continue;
      appendOption(control, authorization.authorizationId,
        authorization.authorizationId + " · " + (authorization.publisherName || authorization.publisherPersonId)
          + " · 至 " + authorization.expiresAt);
    }
  }
}

/** 本人在该期的可执行授权：只来自本人响应里的后端投影，不读 God 状态、不自造。 */
function periodicGrantOf(card, period) {
  const row = periodicRowOf(card, period);
  const grants = (row && row.authorizations) || [];
  return grants.find(authorization => authorization.status === "PENDING"
    && authorization.mine === true) || null;
}

/**
 * 发布表单按本人可执行授权回填草稿/版本/授权：非经理的指定发布人可能看不到草稿行，
 * 这些值仍必须取自后端允许给本人的字段。
 */
function applyPublishGrant() {
  const card = currentPeriodicCard();
  const period = periodicDefaultPeriod(card);
  const grant = periodicGrantOf(card, period);
  const draft = $("eng-draft");
  const version = $("eng-draft-version");
  const auth = $("eng-auth");
  if (!grant) return;
  if (draft && grant.draftId) {
    if (!(draft.children || []).some(option => option.value === grant.draftId)) {
      appendOption(draft, grant.draftId,
        "授权草稿 v" + grant.draftVersion + "（本人被指定发布）",
        { version: String(grant.draftVersion) });
    }
    draft.value = grant.draftId;
  }
  if (version && grant.draftVersion) version.value = String(grant.draftVersion);
  if (auth && grant.authorizationId) auth.value = grant.authorizationId;
}

/** 选草稿时把版本号同步到同一真实版本；不伪造、不猜下一个版本。 */
function syncDraftVersion() {
  const draft = $("eng-draft");
  const version = $("eng-draft-version");
  if (!draft || !version) return;
  const option = (draft.children || []).find(item => item.value === draft.value);
  if (option && option.dataset && option.dataset.version) {
    version.value = option.dataset.version;
  }
}

function periodicUnavailableText(row) {
  const missing = (row && row.unavailableDimensions) || [];
  if (!missing.length) return "";
  return missing.map(item => PERIODIC_DIMENSION_TEXT[item.dimension] || item.dimension)
    .join("、");
}

/** 评分只从本期真实证据里选；该维没有合格证据时保持缺项，不生成 0 分。 */
function renderPeriodicScoreFields(tool, spec, now) {
  const container = $("eng-fields");
  const card = currentPeriodicCard();
  const period = periodicDefaultPeriod(card);
  const row = periodicRowOf(card, period);
  const periodWrapper = document.createElement("div");
  periodWrapper.className = "eng-field";
  periodWrapper.dataset.field = "periodicPeriod";
  const periodLabel = document.createElement("label");
  periodLabel.htmlFor = "eng-period";
  periodLabel.textContent = "考核期间（必填）";
  const periodSelect = document.createElement("select");
  periodSelect.id = "eng-period";
  periodSelect.dataset.field = "periodicPeriod";
  periodSelect.required = true;
  fillPeriodicOptions(periodSelect, "periods", period);
  periodSelect.value = period;
  periodSelect.addEventListener("change", () => renderEngineeringFields(tool));
  periodWrapper.append(periodLabel, periodSelect);
  container.appendChild(periodWrapper);
  state.engineeringFieldIds.push("periodicPeriod");

  for (const [dimension, label] of PERIODIC_DIMENSIONS) {
    const wrapper = document.createElement("div");
    wrapper.className = "eng-field eng-score-row";
    wrapper.dataset.field = "metric:" + dimension;
    const scoreLabel = document.createElement("label");
    scoreLabel.htmlFor = "eng-score-" + dimension;
    scoreLabel.textContent = label + "（0—100，留空表示缺项）";
    const score = document.createElement("input");
    score.type = "text";
    score.autocomplete = "off";
    score.id = "eng-score-" + dimension;
    score.dataset.field = "metricScore";
    score.dataset.dimension = dimension;
    const evidenceLabel = document.createElement("label");
    evidenceLabel.htmlFor = "eng-evidence-" + dimension;
    evidenceLabel.textContent = "本维证据";
    const evidence = document.createElement("select");
    evidence.id = "eng-evidence-" + dimension;
    evidence.dataset.field = "metricEvidence";
    evidence.dataset.dimension = dimension;
    const choices = [];
    for (const entry of (row && row.evidence) || []) {
      if ((entry.dimensions || []).includes(dimension)) {
        choices.push({ ref: entry.ref, asOf: entry.asOf });
      }
    }
    appendOption(evidence, "", choices.length ? "请选择本期合格证据" : "本期无合格证据");
    for (const choice of choices) {
      appendOption(evidence, choice.ref,
        choice.ref + (choice.asOf ? " · 时点 " + choice.asOf : ""), { asOf: choice.asOf });
    }
    evidence.disabled = choices.length === 0;
    score.disabled = choices.length === 0;
    wrapper.append(scoreLabel, score, evidenceLabel, evidence);
    const hint = document.createElement("p");
    hint.className = "hint";
    hint.textContent = choices.length
      ? "证据时点自动取本期截至时点；分数与证据必须同时填写，才算一个完整维度。"
      : "本期没有该维度的合格证据：保留缺项，不补 0。";
    wrapper.appendChild(hint);
    container.appendChild(wrapper);
  }
  const missing = periodicUnavailableText(row);
  if ($("eng-fields-note")) {
    $("eng-fields-note").textContent = (spec.note || "")
      + (missing ? " 本期不可评分维度：" + missing + "（保留缺项）。" : "");
  }
  if ($("eng-tool-note")) {
    $("eng-tool-note").textContent = "将发起：提交本席评分（periodic.score）"
      + " · 第" + (period || "?") + "期";
  }
}

/** 按所选工具重建字段：上一个工具的旧值随容器一起清掉，不会混进新请求。 */
function renderEngineeringFields(tool) {
  state.engineeringTool = tool;
  const container = $("eng-fields");
  const note = $("eng-fields-note");
  const toolNote = $("eng-tool-note");
  if (!container) return;
  container.replaceChildren();
  state.engineeringFieldIds = [];
  const spec = ENGINEERING_TOOL_FIELDS[tool];
  const now = engineeringWorldNow();
  const defaults = engineeringToolDefaults(tool, now);
  if ($("eng-due")) {
    $("eng-due").value = now === null ? "" : String(now + 1000);
  }
  if (!spec) {
    if (note) note.textContent = "该工具暂未提供表单：请先选择公司与本岗位工具。";
    if (toolNote) toolNote.textContent = "";
    return;
  }
  if (tool === "periodic.score") {
    renderPeriodicScoreFields(tool, spec, now);
    return;
  }
  const periodicPeriod = periodicDefaultPeriod(currentPeriodicCard());
  for (const key of spec.fields) {
    const field = ENGINEERING_FIELD_SPECS[key];
    if (!field) continue;
    const wrapper = document.createElement("div");
    wrapper.className = "eng-field";
    wrapper.dataset.field = key;
    const label = document.createElement("label");
    label.htmlFor = field.id;
    label.textContent = field.label + (spec.required.includes(key) ? "（必填）" : "（可选）");
    let control;
    if (field.source) {
      control = document.createElement("select");
      fillPeriodicOptions(control, field.source, periodicPeriod);
      if (field.source === "periods") control.value = periodicPeriod;
      if (field.source === "drafts") {
        if (control.children.length) control.value = control.children[0].value;
        control.addEventListener("change", syncDraftVersion);
      }
    } else if (field.kind === "select") {
      control = document.createElement("select");
      for (const option of field.options || []) {
        appendOption(control, option.value, option.label);
      }
    } else {
      control = document.createElement("input");
      control.type = "text";
      control.autocomplete = "off";
    }
    control.id = field.id;
    control.dataset.field = key;
    const fallback = key === "periodicPeriod" ? periodicPeriod
      : (defaults[key] === undefined ? "" : defaults[key]);
    control.value = fallback;
    control.required = spec.required.includes(key);
    wrapper.append(label, control);
    if (field.hint) {
      const hint = document.createElement("p");
      hint.className = "hint";
      hint.textContent = key === "meetingId"
        && (tool === "board.convene" || tool === "board.conveneWaiver"
          || tool === "board.conveneAppointment")
        ? field.conveneHint : field.hint;
      wrapper.appendChild(hint);
    }
    container.appendChild(wrapper);
    state.engineeringFieldIds.push(key);
  }
  if (spec.fields.includes("draftId")) syncDraftVersion();
  if (tool === "periodic.publish") applyPublishGrant();
  if (note) note.textContent = spec.note || "";
  if (toolNote) {
    toolNote.textContent = "将发起：" + engineeringToolLabel(tool) + "（" + tool + "）";
  }
}

function engineeringFieldValues() {
  const values = {
    dueTimeMillis: $("eng-due") ? $("eng-due").value.trim() : ""
  };
  for (const key of state.engineeringFieldIds || []) {
    const field = ENGINEERING_FIELD_SPECS[key];
    const node = field ? $(field.id) : null;
    values[key] = node ? String(node.value == null ? "" : node.value).trim() : "";
  }
  if (state.engineeringTool === "periodic.score") {
    values.metrics = [];
    for (const [dimension] of PERIODIC_DIMENSIONS) {
      const scoreNode = $("eng-score-" + dimension);
      const evidenceNode = $("eng-evidence-" + dimension);
      const score = scoreNode ? String(scoreNode.value == null ? "" : scoreNode.value).trim() : "";
      const sourceRef = evidenceNode ? String(evidenceNode.value == null ? "" : evidenceNode.value).trim() : "";
      if (!score && !sourceRef) continue;
      values.metrics.push({ dimension, score, sourceRef });
    }
  }
  return values;
}

const ENGINEERING_INT_FIELDS = ["trancheIndex", "units", "docVersion", "durationMillis",
  "materialDeadline", "opensAt", "closesAt", "effectiveAt", "endsAt", "period"];
const ENGINEERING_MONEY_FIELDS = ["amount", "laborLimit", "procurementLimit"];

/** 提交前校验：只针对当前工具可见字段；返回中文提示列表，空表示通过。 */
function engineeringValidate(tool, values) {
  const spec = ENGINEERING_TOOL_FIELDS[tool];
  if (!spec) return ["请选择一个可用工具"];
  const errors = [];
  const fieldLabel = (key) => (ENGINEERING_FIELD_SPECS[key] || {}).label || key;
  if (!values.dueTimeMillis) {
    errors.push("「排程到期」必须填写");
  } else if (!/^\d+$/.test(values.dueTimeMillis)) {
    errors.push("「排程到期」必须是世界毫秒整数");
  }
  for (const key of spec.required) {
    if (!values[key]) errors.push("「" + fieldLabel(key) + "」必须填写");
  }
  for (const key of spec.fields) {
    const value = values[key];
    if (!value) continue;
    if (ENGINEERING_INT_FIELDS.includes(key) && !/^\d+$/.test(value)) {
      errors.push("「" + fieldLabel(key) + "」必须是非负整数");
    }
    if (ENGINEERING_MONEY_FIELDS.includes(key) && !/^\d{1,15}(\.\d{1,2})?$/.test(value)) {
      errors.push("「" + fieldLabel(key) + "」必须是最多两位小数的非负金额");
    }
  }
  if (values.units && /^\d+$/.test(values.units) && Number(values.units) <= 0) {
    errors.push("「" + fieldLabel("units") + "」必须大于 0");
  }
  if (values.amount && /^\d+(\.\d{1,2})?$/.test(values.amount) && Number(values.amount) <= 0) {
    errors.push("「" + fieldLabel("amount") + "」必须大于 0");
  }
  if (values.materialDeadline && values.opensAt
      && Number(values.opensAt) < Number(values.materialDeadline)) {
    errors.push("「开会时点」不能早于「材料截止时点」");
  }
  if (values.opensAt && values.closesAt && Number(values.closesAt) <= Number(values.opensAt)) {
    errors.push("「表决截止时点」必须晚于「开会时点」");
  }
  if (tool === "board.proposeAppointment" && values.appointAction === "APPOINT") {
    if (!values.endsAt) {
      errors.push("任命必须填写「任职期限」");
    } else if (values.effectiveAt && Number(values.endsAt) <= Number(values.effectiveAt)) {
      errors.push("「任职期限」必须晚于「任职生效时点」");
    }
  }
  if (tool === "board.proposeBudget" && !values.laborLimit && !values.procurementLimit) {
    errors.push("预算提案至少要填一个人工或采购上限");
  }
  if (tool.startsWith("periodic.")) {
    const period = values.periodicPeriod;
    if (period && (!/^\d+$/.test(period) || Number(period) < 1)) {
      errors.push("「考核期间」必须是大于等于 1 的整数");
    }
    if (values.durationMillis && !/^\d+$/.test(values.durationMillis)) {
      errors.push("「本次决策工时」必须是非负整数");
    } else if (values.durationMillis === "0") {
      errors.push("「本次决策工时」对周期动作必须大于 0");
    }
    if (tool === "periodic.score") {
      const metrics = values.metrics || [];
      if (!metrics.length) {
        errors.push("评分至少填写一个完整维度（分数与本期证据）");
      }
      for (const metric of metrics) {
        const name = PERIODIC_DIMENSION_TEXT[metric.dimension] || metric.dimension;
        if (!metric.score) {
          errors.push("「" + name + "」缺少分数");
        } else if (!/^\d{1,3}(\.\d{1,2})?$/.test(metric.score)
            || Number(metric.score) > 100) {
          errors.push("「" + name + "」必须是 0—100 的分数");
        }
        if (!metric.sourceRef) errors.push("「" + name + "」必须选择本期合格证据");
      }
    }
    if (["periodic.approve", "periodic.authorize", "periodic.publish"].includes(tool)) {
      if (values.draftId && !values.draftVersion) {
        errors.push("「草稿版本」必须填写");
      } else if (values.draftVersion && !/^\d+$/.test(values.draftVersion)) {
        errors.push("「草稿版本」必须是整数");
      }
      if (values.draftId && values.draftVersion) {
        const drafts = periodicDraftsOf(currentPeriodicCard(), period);
        const match = drafts.find(draft => draft.draftId === values.draftId
          && String(draft.version) === String(values.draftVersion));
        // 非经理的指定发布人看不到他人草稿行；本人可执行授权里的草稿绑定同样合法。
        const grant = tool === "periodic.publish"
          ? periodicGrantOf(currentPeriodicCard(), period) : null;
        const grantMatch = !!grant && grant.draftId === values.draftId
          && String(grant.draftVersion) === String(values.draftVersion);
        if (!match && !grantMatch) {
          errors.push("所选草稿不属于该期间或版本不一致，请重新选择");
        }
      }
    }
    if (tool === "periodic.authorize") {
      const now = Number(state.personEngineering && state.personEngineering.timeMillis);
      if (values.expiresAt && Number.isFinite(now) && Number(values.expiresAt) <= now) {
        errors.push("「授权失效时点」必须晚于当前世界时点");
      }
    }
  }
  return errors;
}

/** 把焦点放到第一个缺失的必填字段，避免用户在大表里找。 */
function engineeringFocusFirstInvalid(tool, values) {
  const spec = ENGINEERING_TOOL_FIELDS[tool];
  if (!spec) return;
  for (const key of spec.required) {
    if (values[key]) continue;
    const field = ENGINEERING_FIELD_SPECS[key];
    const node = field ? $(field.id) : null;
    if (node && typeof node.focus === "function") node.focus();
    return;
  }
  if (tool === "periodic.score") {
    for (const [dimension] of PERIODIC_DIMENSIONS) {
      const scoreNode = $("eng-score-" + dimension);
      const evidenceNode = $("eng-evidence-" + dimension);
      const score = scoreNode ? String(scoreNode.value || "").trim() : "";
      const ref = evidenceNode ? String(evidenceNode.value || "").trim() : "";
      if ((score && !ref) || (!score && ref)) {
        const target = score && !ref ? evidenceNode : scoreNode;
        if (target && typeof target.focus === "function") target.focus();
        return;
      }
    }
  }
}

async function renderPersonalEngineering(generation) {
  let result;
  try {
    result = await request("GET", "/api/me/engineering");
  } catch (failure) {
    return;
  }
  if (!isCurrent(generation) || state.mode !== "person") return;
  if (result.status === 401) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) return;
  const view = result.payload;
  state.personEngineering = view;
  const card = $("person-engineering-card");
  if (!card) return;
  card.hidden = false;
  $("person-engineering-note").textContent = view.enabled
    ? "同一确认记录 " + view.position + " · 世界时点 " + view.timeMillis + " 毫秒；只显示本人岗位内容。"
    : (view.note || "此运行未启用工程经营。");
  fillPersonalEngineeringCompanies(view.companies || []);
  fillEngineeringSelectors(view.companies || []);
  fillNotes("person-engineering-notes", view.notes);
}

/** 只把当前工具可见字段放进请求；空串不发，显式 "0" 原样保留。 */
function engineeringActionBody(tool, values) {
  const view = state.personEngineering || {};
  state.engineeringSubmitSeq = (state.engineeringSubmitSeq || 0) + 1;
  const stamp = Date.now() + "-" + state.engineeringSubmitSeq;
  const body = {
    expectedPosition: String(view.position || ""),
    commandId: "web-eng-" + stamp,
    taskId: "web-eng-" + stamp + "-task",
    dueTimeMillis: values.dueTimeMillis,
    companyId: $("eng-company").value,
    tool: tool
  };
  const spec = ENGINEERING_TOOL_FIELDS[tool];
  for (const key of (spec ? spec.fields : [])) {
    const raw = values[key];
    if (raw === undefined || raw === "") continue;
    if (key === "recused") {
      body.recused = raw === "true";
      continue;
    }
    body[key === "periodicPeriod" ? "period" : key] = raw;
  }
  if (tool === "periodic.score") {
    const metrics = (values.metrics || []).filter(metric => metric.score && metric.sourceRef);
    if (metrics.length) body.metrics = metrics;
  }
  return body;
}

/**
 * 排程一次真实阅读：只用于当前会话人物；读完与否仍由信息模块在到期时判定。
 * 传入的文档与版本必须来自本人卡片/本人投影，页面不代造标识。
 */
async function scheduleDocumentReading(companyId, docId, version, label) {
  const view = state.personEngineering || {};
  if (!view.position) {
    setMessage("engineering-action-error", "当前没有可用的世界位置，请刷新后再试。");
    return;
  }
  state.engineeringSubmitSeq = (state.engineeringSubmitSeq || 0) + 1;
  const stamp = Date.now() + "-" + state.engineeringSubmitSeq;
  let result;
  try {
    result = await request("POST", "/api/me/read", {
      expectedPosition: String(view.position),
      commandId: "web-read-" + stamp,
      taskId: "web-read-" + stamp + "-task",
      dueTimeMillis: String(Number(view.timeMillis) + 1000),
      docId: docId,
      version: String(version)
    });
  } catch (failure) {
    setMessage("engineering-action-error", failure.message);
    return;
  }
  if (result.status === 401) { sessionLost(); return; }
  if (result.status === 409) {
    setMessage("engineering-action-error", "世界已推进到新的确认位置，请刷新后再排程阅读。");
    return;
  }
  if (result.status !== 200 || !result.payload) {
    const reason = result.payload && result.payload.message ? result.payload.message
      : "服务返回 " + result.status;
    setMessage("engineering-action-error", "阅读排程被拒绝：" + reason);
    return;
  }
  setMessage("engineering-action-result", "已排程阅读：公司 " + companyId + " · " + label
    + " v" + version + " · 命令 " + result.payload.receipt
    + "。世界推进到阅读完成后再刷新，才会显示“本人已读完”。");
  await renderPersonalEngineering(state.generation);
}

/** 周期材料阅读入口：标签固定为“第 N 期材料”，其余与通用阅读一致。 */
async function schedulePeriodicReading(companyId, period, docId, version) {
  return scheduleDocumentReading(companyId, docId, version, "第" + period + "期材料");
}

async function submitEngineeringAction(event) {
  event.preventDefault();
  setMessage("engineering-action-result", "");
  setMessage("engineering-action-error", "");
  if (!state.personEngineering || state.mode !== "person") {
    setMessage("engineering-action-error", "当前不是人物会话，无法以任何人物身份提交。");
    return;
  }
  const tool = $("eng-tool") ? $("eng-tool").value : "";
  const values = engineeringFieldValues();
  const errors = engineeringValidate(tool, values);
  if (errors.length) {
    setMessage("engineering-action-error", "还不能提交：" + errors.slice(0, 3).join("；")
      + (errors.length > 3 ? "；等共 " + errors.length + " 项" : "") + "。");
    engineeringFocusFirstInvalid(tool, values);
    return;
  }
  const body = engineeringActionBody(tool, values);
  let result;
  try {
    result = await request("POST", "/api/me/engineering/action", body);
  } catch (failure) {
    setMessage("engineering-action-error", failure.message);
    return;
  }
  if (state.mode !== "person") return;
  if (result.status === 401) { sessionLost(); return; }
  if (result.status === 409) {
    setMessage("engineering-action-error", "世界已推进到新的确认位置，请刷新后再提交。");
    return;
  }
  if (result.status !== 200 || !result.payload) {
    const reason = result.payload && result.payload.message ? result.payload.message
      : "服务返回 " + result.status;
    setMessage("engineering-action-error", "排程被拒绝：" + reason);
    return;
  }
  const view = result.payload;
  const periodicNote = String(view.tool || "").startsWith("periodic.")
    ? "周期动作只显示为已排程/待执行；到期 FINISH 后才产生草稿、评分、批准或公开版本，"
      + "发布前不能当已完成披露。"
    : "";
  setMessage("engineering-action-result", "已排程：" + engineeringToolLabel(view.tool)
    + " · 公司 " + view.companyId + " · 命令 " + view.receipt
    + "。世界推进到到期时点后才落账，届时以真实回报为准。" + periodicNote);
  await renderPersonalEngineering(state.generation);
  await refreshPerson();
}

$("engineering-company").addEventListener("change", () => {
  state.engineeringCompany = $("engineering-company").value;
  loadEngineering(state.generation, state.pageVersion);
});
$("engineering-refresh").addEventListener("click", () => {
  loadEngineering(state.generation, state.pageVersion);
});
$("engineering-report-filter").addEventListener("change", () => {
  if (state.engineering) renderEngineeringReports(state.engineering);
});
$("eng-company").addEventListener("change", () => {
  state.engineeringFieldIds = [];
  refreshEngineeringTools();
});
$("eng-tool").addEventListener("change", () => {
  renderEngineeringFields($("eng-tool").value);
});
$("engineering-action-form").addEventListener("submit", (event) => {
  guard(submitEngineeringAction(event));
});
$("person-bank-kind").addEventListener("change", togglePersonalBankRows);
$("person-bank-subject").addEventListener("change", togglePersonalBankRows);
$("person-bank-submit").addEventListener("click", () => guard(submitPersonalBankAction()));
