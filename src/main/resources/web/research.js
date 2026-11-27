"use strict";

const RESEARCH_ACTIONS = {
  LICENSE_SIGN: "接受成果许可要约", LICENSE_DELIVER: "交付已签使用权", LICENSE_COLLECT: "收取到期许可款",
  REGISTER: "登记研发项目", BEGIN: "开始一个研发阶段", APPROVE: "按授权转为可用资产",
  CONVENE: "召集商业化会议", PROPOSE: "提出商业化议案", IMPLEMENT: "实施会议决议"
};
const RESEARCH_STAGES = { RESEARCH: "研究", DEVELOPMENT: "开发", REGISTRATION: "注册" };
const RESEARCH_STATUSES = { UNREGISTERED: "尚未登记", ACTIVE: "已登记", PLANNED: "尚未开始",
  IN_PROGRESS: "进行中", COMPLETED: "已完成", FAILED: "失败", APPROVED: "已批准可用" };

function researchDuration(millis) {
  const value = BigInt(millis);
  for (const [unit, label] of [[86400000n, "天"], [3600000n, "小时"], [60000n, "分钟"], [1000n, "秒"]]) {
    if (value >= unit && value % unit === 0n) return (value / unit).toString() + " " + label;
  }
  return value.toString() + " 毫秒";
}

function placeResearchPanel() {
  const panel = document.querySelector('[data-panel="research"]');
  if (!panel) return;
  const host = state.mode === "person" ? $("person") : $("admin");
  if (host && panel.parentElement !== host) host.appendChild(panel);
  panel.hidden = state.mode !== "person" && state.page !== "research";
}

function clearResearchDom() {
  state.researchView = null;
  for (const id of ["research-projects", "research-financial"]) {
    if ($(id)) $(id).replaceChildren();
  }
  if ($("research-form")) $("research-form").hidden = true;
  for (const id of ["research-due", "research-meeting", "research-material-deadline", "research-opens",
    "research-closes", "research-related", "research-evidence-1", "research-evidence-2", "research-evidence-3"]) {
    if ($(id)) $(id).value = "";
  }
  if ($("research-duration")) $("research-duration").value = "1000";
  for (const i of [1, 2, 3]) if ($("research-version-" + i)) $("research-version-" + i).value = "1";
  if ($("research-financial-card")) $("research-financial-card").hidden = true;
  for (const id of ["research-note", "research-cut", "research-result"]) {
    if ($(id)) $(id).textContent = "";
  }
}

async function loadResearch(generation, pageVersion) {
  const mode = state.mode;
  const version = pageVersion ?? state.pageVersion;
  const current = () => isCurrent(generation) && state.mode === mode
    && state.pageVersion === version && (mode === "person" || state.page === "research");
  try {
    const result = await request("GET", mode === "admin" ? "/api/admin/research" : "/api/me/research");
    if (!current()) return;
    if (result.status === 401 || result.status === 403) { sessionLost(); return; }
    if (result.status !== 200 || !result.payload) throw new Error("读取失败（" + result.status + "）");
    renderResearch(result.payload);
  } catch (error) {
    if (!current()) return;
    clearResearchDom();
    $("research-note").textContent = error.message || "研发页面读取失败，请刷新。";
  }
}

function researchLine(parent, textValue) {
  const p = document.createElement("p");
  p.textContent = textValue;
  parent.appendChild(p);
}

function renderResearch(view) {
  state.researchView = view;
  $("research-financial-card").hidden = state.mode !== "admin" || !view.companyReport;
  $("research-note").textContent = view.note || "";
  $("research-cut").textContent = [view.companyName || view.companyId,
    view.position == null ? null : "确认记录 " + view.position, "世界时点 " + view.timeMillis + " 毫秒"]
    .filter(Boolean).join(" · ");
  for (const id of ["research-projects", "research-financial"]) $(id).replaceChildren();
  const projects = view.projects || [];
  if (view.licensing) {
    const license = document.createElement("section");
    const heading = document.createElement("h3");
    heading.textContent = "成果使用许可";
    license.append(heading);
    researchLine(license, view.licensing.terms);
    const totals = view.licensing.totals;
    researchLine(license, "累计交付收入 " + totals.revenue + " 元，累计实收 " + totals.cashReceived +
      " 元，期末应收 " + totals.receivable + " 元。");
    for (const contract of Object.values(view.licensing.contracts || {})) {
      const state = contract.paidAt != null ? "已收款" : contract.deliveredAt != null ? "已交付，等待收款" : "已签，待交付";
      researchLine(license, contract.projectId + " · " + state + " · 签约时点 " + contract.signedAt
        + (contract.dueAt == null ? "" : " · 款项到期 " + contract.dueAt));
    }
    $("research-projects").appendChild(license);
  }
  for (const project of projects) {
    const article = document.createElement("article");
    article.className = "card";
    const title = document.createElement("h3");
    title.textContent = "项目 " + project.projectId;
    article.appendChild(title);
    researchLine(article, "预算 " + project.budget + " · 可用后摊销期限 " + project.amortizationDays + " 天");
    for (const stage of project.stages || []) {
      researchLine(article, (RESEARCH_STAGES[stage.stage] || stage.stage) + "：成本 " + stage.cost
        + "，阶段持续 " + researchDuration(stage.durationMillis));
    }
    if (state.mode === "admin") {
      researchLine(article, "真实状态：" + (RESEARCH_STATUSES[project.status] || project.status)
        + (project.approvalRef ? " · 批准时点 " + project.approvedAt : ""));
      for (const outcome of project.outcomes || []) {
        researchLine(article, (RESEARCH_STAGES[outcome.stage] || outcome.stage) + "："
          + (RESEARCH_STATUSES[outcome.status] || outcome.status)
          + (outcome.evidenceRef ? " · 证据 " + outcome.evidenceRef : ""));
      }
      if (project.amortized != null) researchLine(article, "累计摊销 " + project.amortized);
    }
    $("research-projects").appendChild(article);
  }
  if (state.mode === "admin" && view.companyReport) {
    for (const [field, label] of [["assets", "资产"], ["liabilities", "负债"], ["equity", "权益"],
      ["income", "收入"], ["expense", "费用"]]) {
      const section = document.createElement("section");
      const heading = document.createElement("h3");
      heading.textContent = label;
      section.appendChild(heading);
      const rows = document.createElement("dl");
      for (const [key, amount] of Object.entries(view.companyReport[field] || {})) {
        const term = document.createElement("dt");
        const value = document.createElement("dd");
        term.textContent = key;
        value.textContent = amount;
        rows.appendChild(term);
        rows.appendChild(value);
      }
      section.appendChild(rows);
      $("research-financial").appendChild(section);
    }
  }
  const actions = view.actions || [];
  $("research-form").hidden = state.mode !== "person" || !view.enabled || actions.length === 0;
  if (state.mode === "person" && view.enabled) {
    const selected = $("research-action").value;
    $("research-action").replaceChildren(...actions.map(action => new Option(RESEARCH_ACTIONS[action] || action, action)));
    if (actions.includes(selected)) $("research-action").value = selected;
    const project = $("research-project").value;
    $("research-project").replaceChildren(...projects.map(item => new Option(item.projectId, item.projectId)));
    if (projects.some(item => item.projectId === project)) $("research-project").value = project;
    if (!$("research-due").value) $("research-due").value = (BigInt(view.timeMillis) + 2000n).toString();
    researchFields();
  }
}

function researchFields() {
  const action = $("research-action").value;
  const license = action.startsWith("LICENSE_");
  $("research-due").readOnly = license;
  if (license) $("research-due").value = state.researchView.licenseEarliestFinishMillis;
  for (const field of document.querySelectorAll("[data-research-actions]")) {
    const visible = field.dataset.researchActions.split(" ").includes(action);
    field.hidden = !visible;
    for (const input of field.querySelectorAll("input,select,textarea")) input.disabled = !visible;
  }
}

function researchInteger(id) {
  const value = $(id).value.trim();
  if (!/^(0|[1-9][0-9]*)$/.test(value) || BigInt(value) > 9223372036854775807n) {
    throw new Error("请填写有效的非负整数：" + ($(id).labels?.[0]?.textContent || id));
  }
  return value;
}

async function submitResearch(event) {
  event.preventDefault();
  const generation = state.generation;
  const view = state.researchView;
  if (state.mode !== "person" || !view?.enabled) return;
  const action = $("research-action").value;
  const id = "web-rnd-" + crypto.randomUUID();
  const command = { expectedPosition: view.observationToken ?? view.position, commandId: id, taskId: id + "-task",
    dueTimeMillis: researchInteger("research-due"), companyId: view.companyId, action };
  if (action.startsWith("LICENSE_") || ["REGISTER", "BEGIN", "PROPOSE", "APPROVE"].includes(action)) command.projectId = $("research-project").value;
  if (["REGISTER", "BEGIN", "PROPOSE", "APPROVE"].includes(action)) command.durationMillis = researchInteger("research-duration");
  if (action === "BEGIN") command.stage = $("research-stage").value;
  if (["CONVENE", "PROPOSE", "IMPLEMENT", "APPROVE"].includes(action)) command.meetingId = $("research-meeting").value.trim();
  if (action === "CONVENE") {
    command.materialDeadline = researchInteger("research-material-deadline");
    command.opensAt = researchInteger("research-opens");
    command.closesAt = researchInteger("research-closes");
  }
  if (action === "PROPOSE") {
    command.relatedSeats = $("research-related").value.split(/[，,]/).map(s => s.trim()).filter(Boolean);
    command.materials = [1, 2, 3].map(i => ({ materialId: "m" + i,
      sourceRef: $("research-evidence-" + i).value.trim(), version: researchInteger("research-version-" + i), asOf: view.timeMillis }));
  }
  $("research-submit").disabled = true;
  try {
    const result = await request("POST", "/api/me/research/action", command);
    if (!isCurrent(generation) || state.mode !== "person") return;
    if (result.status === 401 || result.status === 403) { sessionLost(); return; }
    $("research-result").textContent = result.status === 200
      ? "已排程，等待世界推进；回执：" + result.payload.receipt
      : "未受理：" + (result.payload?.message || "请刷新后重试");
    await loadResearch(generation, null);
  } finally {
    if (isCurrent(generation) && state.mode === "person") $("research-submit").disabled = false;
  }
}

document.addEventListener("DOMContentLoaded", () => {
  $("research-action").addEventListener("change", researchFields);
  $("research-form").addEventListener("submit", event => {
    const generation = state.generation;
    submitResearch(event).catch(error => {
      if (isCurrent(generation) && state.mode === "person") $("research-result").textContent = error.message || "提交失败，请刷新。";
    });
  });
});
