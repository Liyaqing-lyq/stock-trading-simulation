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
  page: "overview"
};

const ADMIN_TABLES = ["accounts", "owners", "pending", "trades", "dividends", "keys"];
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
guard(boot());
