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
  details: {}
};

const ADMIN_TABLES = ["accounts", "owners", "pending", "trades", "dividends", "keys"];
const DETAIL_PAGES = {
  company: "/api/admin/company",
  performance: "/api/admin/performance",
  org: "/api/admin/people",
  information: "/api/admin/information"
};
const DETAIL_TABLES = ["company-report", "company-inventory", "company-orders",
  "company-batches", "company-contracts", "company-receivables", "company-bills",
  "company-seats", "company-meetings", "company-budgets", "company-commitments",
  "company-reviews", "company-evidence", "company-reports", "performance-accounts",
  "performance-owners", "performance-holdings", "performance-flows", "org-parties",
  "org-owners", "org-appointments", "org-mandates", "org-reports",
  "information-documents", "information-active", "information-closed",
  "information-observations", "information-readers", "information-reports"];
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
  state.position = null;
  state.routeRevision = null;
  $("routes-list").replaceChildren();
  $("routes-result").textContent = "";
  $("route-provider").textContent = "";
  $("trade-chart").replaceChildren();
  $("chart-caption").textContent = "尚无真实成交。";
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
  selectPage("overview");
  setStatus("管理员视角");
  await refreshAdmin();
}

async function refreshAdmin() {
  const generation = state.generation;
  let result;
  try {
    result = await request("GET", "/api/admin/view");
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "admin") {
      setMessage("admin-error", failure.message);
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin") {
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
  await refreshKeys(generation);
  await refreshRoutes(generation);
  if (DETAIL_PAGES[state.page]) {
    state.details[state.page] = null;
    await loadDetail(state.page);
  }
}

function renderAdmin(view) {
  state.position = view.position;
  $("context-time").textContent = view.runId + " · " + view.timeMillis + " 毫秒";
  $("metric-accounts").textContent = String(view.accounts.length);
  $("metric-pending").textContent = String(view.pendingCount);
  $("metric-trades").textContent = String(view.trades.length);
  $("metric-position").textContent = view.position;
  if (!$("advance-target").value) $("advance-target").value = String(BigInt(view.timeMillis) + 1000n);
  renderTradeChart(view.trades);
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

async function refreshKeys(generation) {
  let result;
  try {
    result = await request("GET", "/api/admin/keys");
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "admin") {
      setMessage("admin-error", failure.message);
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin") {
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
  people: ["人物视角", "以各自的权限观察世界，保留信息边界。"],
  settings: ["模型与密钥", "为五类场景配置模型、密钥槽位和调用预算。"]
};
function selectPage(page) {
  if (!pages[page]) return;
  clearSecrets();
  state.page = page;
  $("page-title").textContent = pages[page][0];
  $("page-description").textContent = pages[page][1];
  document.querySelectorAll("[data-panel]").forEach(node => { node.hidden = node.dataset.panel !== page; });
  document.querySelectorAll("[data-page]").forEach(node => {
    if (node.dataset.page === page) node.setAttribute("aria-current", "page");
    else node.removeAttribute("aria-current");
  });
  if (DETAIL_PAGES[page]) guard(loadDetail(page));
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
async function refreshRoutes(generation) {
  if (!isCurrent(generation) || state.mode !== "admin") return;
  const result = await request("GET", "/api/admin/routes");
  if (!isCurrent(generation) || state.mode !== "admin") return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) {
    setMessage("routes-result", "场景配置读取失败，请刷新重试。");
    state.routeRevision = null;
    return;
  }
  const config = result.payload;
  state.routeRevision = config.revision;
  $("route-provider").textContent = "DeepSeek · " + config.baseUrl + " · 配置版本 " + config.revision;
  $("route-runtime").textContent = config.connected
    ? "场景路由已连接运行，实际调用以运行记录为准。"
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
function renderTradeChart(trades) {
  const svg = $("trade-chart"); svg.replaceChildren(); svg.hidden = true;
  if (!trades.length) { $("chart-caption").textContent = "尚无真实成交。"; return; }
  const symbol = trades[trades.length - 1].symbol;
  const points = trades.filter(trade => trade.symbol === symbol).slice(-500);
  const prices = points.map(trade => Number(trade.price));
  if (prices.some(value => !Number.isFinite(value))) {
    $("chart-caption").textContent = "价格超出绘图范围，请查看下表精确数据。"; return;
  }
  const first = BigInt(points[0].timeMillis), last = BigInt(points[points.length - 1].timeMillis);
  const min = Math.min(...prices), max = Math.max(...prices), span = max - min || Math.max(Math.abs(max) * .02, .01);
  const low = min - span * .15, high = max + span * .15;
  const denominator = Number(last - first) || 1;
  const y = value => 205 - (value - low) / (high - low) * 180;
  for (let i = 0; i < 4; i++) {
    const value = low + (high - low) * i / 3, lineY = y(value);
    svg.append(svgNode("line", { x1: 72, y1: lineY, x2: 878, y2: lineY, class: "chart-grid" }));
    svg.append(svgNode("text", { x: 61, y: lineY + 4, "text-anchor": "end", class: "chart-label" }, value.toFixed(2)));
  }
  const coords = points.map((trade, index) => [72 + Number(BigInt(trade.timeMillis) - first) / denominator * 806, y(prices[index])]);
  svg.append(svgNode("polyline", { points: coords.map(point => point.join(",")).join(" "), class: "chart-line" }));
  for (const [index, point] of coords.entries()) {
    const dot = svgNode("circle", { cx: point[0], cy: point[1], r: 3, class: "chart-point" });
    dot.append(svgNode("title", {}, points[index].timeMillis + " 毫秒 · " + points[index].price + " 元")); svg.append(dot);
  }
  svg.append(svgNode("text", { x: 72, y: 234, class: "chart-label" }, String(first)));
  svg.append(svgNode("text", { x: 878, y: 234, "text-anchor": "end", class: "chart-label" }, String(last)));
  $("chart-caption").textContent = symbol + " · 最近 " + points.length + " 笔真实成交（最多 500 笔）· 纵轴：元；横轴：模拟毫秒";
  svg.hidden = false;
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
  state.details = {};
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
  document.querySelectorAll("#admin .empty").forEach(node => { node.hidden = true; });
}

async function loadDetail(page) {
  const url = DETAIL_PAGES[page];
  if (!url || state.mode !== "admin") return;
  const generation = state.generation;
  let result;
  try {
    result = await request("GET", url);
  } catch (failure) {
    if (isCurrent(generation) && state.mode === "admin") setMessage("admin-error", failure.message);
    return;
  }
  if (!isCurrent(generation) || state.mode !== "admin") return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) {
    setMessage("admin-error", "读取" + pages[page][0] + "失败（" + result.status + "），请稍后重试。");
    return;
  }
  state.details[page] = result.payload;
  renderDetail(page, result.payload);
}

function renderDetail(page, view) {
  if (page === "company") renderCompany(view);
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

guard(boot());
