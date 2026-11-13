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
  personDialogue: null
};

const ADMIN_TABLES = ["accounts", "owners", "pending", "trades", "dividends", "keys"];
const DETAIL_PAGES = {
  company: "/api/admin/company",
  media: "/api/admin/media",
  interview: "/api/admin/interview",
  performance: "/api/admin/performance",
  org: "/api/admin/people",
  information: "/api/admin/information"
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
  "market-bars", "market-gaps"];
const PERSON_TABLES = ["person-accounts", "person-holdings", "person-market"];

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
  $("person-name").textContent = "—";
  $("person-summary").replaceChildren();
  for (const id of PERSON_TABLES) {
    fillTable(id, [], () => undefined);
  }
  $("person-catalog").textContent = "";
  setMessage("person-error", "");
}

function showMode(mode) {
  state.mode = mode;
  document.body.dataset.mode = mode;
  $("navigation").hidden = mode !== "admin";
  $("refresh").hidden = mode === "login";
  $("context-time").textContent = mode === "login" ? "本机实验环境" : "读取确认状态…";
  nextGeneration();
  if (mode !== "admin") {
    clearAdminDom();
  }
  if (mode !== "person") {
    clearPersonDom();
  }
  show("login", mode === "login");
  show("admin", mode === "admin");
  show("person", mode === "person");
  $("logout").hidden = mode === "login";
}

function sessionLost() {
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
  await renderPersonalInterview((view.media && view.media.tools) || view.tools, generation);
  if (!isCurrent(generation) || state.mode !== "person") return;
  $("context-time").textContent = "人物观察 · " + view.timeMillis + " 毫秒";
  $("person-name").textContent = view.personPartyId;

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
  media: ["媒体采编", "选题、审阅、发布与更正，都有实际工时和原始版本。"],
  people: ["人物视角", "以各自的权限观察世界，保留信息边界。"],
  models: ["模型运行", "让人物依据自己的观察完成一次判断，查看实际输入与回报。"],
  settings: ["模型与密钥", "为五类场景配置模型、密钥槽位和调用预算。"],
  interview: ["采访预约", "查看采访预约、逐轮对话、实际阅读与机构工时。"]
};
function selectPage(page, refresh = true) {
  if (!pages[page]) return;
  clearSecrets();
  stopLivePoll();
  if (state.page === "market" && page !== "market") clearMarketDom();
  if (state.page === "media" && page !== "media") clearMediaDom();
  if (state.page === "interview" && page !== "interview") clearInterviewDom();
  if (state.page === "performance" && page !== "performance") clearPerformanceHistoryDom();
  state.page = page;
  state.pageVersion += 1;
  $("page-title").textContent = pages[page][0];
  $("page-description").textContent = pages[page][1];
  document.querySelectorAll("[data-panel]").forEach(node => { node.hidden = node.dataset.panel !== page; });
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
    "information-reports-count"]) {
    if ($(id)) $(id).textContent = "";
  }
  for (const id of ["company-rules", "company-charter", "company-notes", "performance-notes",
    "org-notes", "information-notes"]) {
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
  const selected = new Set(Array.from(document.querySelectorAll('input[name="live-person"]:checked'), node => node.value));
  state.capabilities = result.payload;
  const list = $("live-persons"); list.replaceChildren();
  const legend = document.createElement("legend"); legend.textContent = "本轮参与人物"; list.appendChild(legend);
  for (const person of result.payload.persons) {
    const label = document.createElement("label"), input = document.createElement("input");
    input.type = "checkbox"; input.name = "live-person"; input.value = person.personPartyId;
    input.checked = selected.has(person.personPartyId) || (first && person.personPartyId === "alice");
    label.append(input, document.createTextNode(person.name + " · " + (LIVE_SCENES[person.sceneId] || person.sceneId)));
    list.appendChild(label);
  }
  return true;
}
$("media-story-select").addEventListener("change", () => guard(loadMediaStory()));


guard(boot());


// 模型运行：只读轮询用于显示，服务端独立处理完成结果。
const LIVE_PHASES = { SCRIPTED: "脚本模式 · 未接模型", IDLE: "等待开始", READY: "等待开始",
  RUNNING: "本轮运行中", STOPPING: "等待已发请求结束", PAUSED: "已暂停处理", FAULT: "需要重启恢复" };
const LIVE_SCENES = { research: "机构研究", trading: "交易执行", governance: "公司治理（待接入）",
  media: "媒体采编", evaluation: "评估实验（待接入）" };
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
function renderLive(v) {
  state.live = v;
  renderAdminCut(v);
  $("live-cut").textContent = "运行 " + v.runId + " · 确认记录 " + v.position + " · 模拟时点 " + v.timeMillis + " 毫秒";
  $("live-phase").textContent = LIVE_PHASES[v.phase] || v.phase;
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
  const body = { expectedPosition: state.live.position };
  if (action === "start") {
    const persons = Array.from(document.querySelectorAll('input[name="live-person"]:checked'), node => node.value);
    if (!persons.length) { setMessage("live-error", "请选择至少一位人物。"); return; }
    body.persons = persons;
    body.expectedRevision = state.routeRevision;
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
