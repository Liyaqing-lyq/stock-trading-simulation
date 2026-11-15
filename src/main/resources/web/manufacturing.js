"use strict";
const MANUFACTURING_ACTIONS = { SIGN: "签订销售合同", PURCHASE_ORDER: "采购原料", PRODUCTION: "安排生产",
  DELIVERY: "交付商品", COLLECT: "收回应收款", PAY_BILL: "支付费用欠款" };

function placeManufacturingPanel() {
  const panel = document.querySelector('[data-panel="manufacturing"]');
  if (!panel) return;
  const host = state.mode === "person" ? $("person") : $("admin");
  if (host && panel.parentElement !== host) host.appendChild(panel);
  panel.hidden = state.mode !== "person" && state.page !== "manufacturing";
}

function clearManufacturingDom() {
  state.manufacturingView = null;
  for (const id of ["manufacturing-summary", "manufacturing-reports", "manufacturing-operations", "manufacturing-receipts"]) $(id)?.replaceChildren();
  for (const id of ["manufacturing-note", "manufacturing-cut", "manufacturing-result", "manufacturing-inventory"]) {
    if ($(id)) $(id).textContent = "";
  }
  for (const id of ["manufacturing-due", "manufacturing-business", "manufacturing-delivery-id",
    "manufacturing-promised"]) if ($(id)) $(id).value = "";
  if ($("manufacturing-form")) $("manufacturing-form").hidden = true;
  if ($("manufacturing-audit")) $("manufacturing-audit").hidden = true;
  for (const id of ["manufacturing-business-state", "manufacturing-own-results"]) if ($(id)) $(id).hidden = true;
  $("manufacturing-source")?.replaceChildren();
}

async function loadManufacturing(generation, pageVersion) {
  const mode = state.mode, version = pageVersion ?? state.pageVersion;
  const current = () => isCurrent(generation) && state.mode === mode && state.pageVersion === version
    && (mode === "person" || state.page === "manufacturing");
  try {
    const result = await request("GET", mode === "admin" ? "/api/admin/manufacturing" : "/api/me/manufacturing");
    if (!current()) return;
    if ([401, 403].includes(result.status)) { sessionLost(); return; }
    if (result.status !== 200 || !result.payload) throw new Error("制造经营读取失败，请刷新。");
    renderManufacturing(result.payload);
  } catch (failure) {
    if (!current()) return;
    clearManufacturingDom();
    $("manufacturing-note").textContent = failure.message || "读取失败，请刷新。";
  }
}

function renderManufacturing(view) {
  state.manufacturingView = view;
  $("manufacturing-note").textContent = view.note || "";
  $("manufacturing-cut").textContent = "确认记录 " + view.position + " · 世界时点 " + view.timeMillis + " 毫秒";
  $("manufacturing-summary").replaceChildren();
  $("manufacturing-reports").replaceChildren();
  $("manufacturing-operations").replaceChildren();
  $("manufacturing-receipts").replaceChildren();
  $("manufacturing-inventory").textContent = "";
  $("manufacturing-business-state").hidden = !view.inventory;
  $("manufacturing-own-results").hidden = state.mode !== "person" || !view.receipts;
  if (view.inventory) {
    const stock = view.inventory;
    $("manufacturing-inventory").textContent = "原材料 " + stock.rawQuantity + "（成本 " + stock.rawCost
      + "）；产成品 " + stock.finishedQuantity + "（成本 " + stock.finishedCost + "）。";
    for (const row of view.operations || []) {
      const tr = document.createElement("tr");
      for (const value of [row.kind, row.id, row.status, row.quantity, row.amount, row.due]) {
        const td = document.createElement("td"); td.textContent = value; tr.appendChild(td);
      }
      $("manufacturing-operations").appendChild(tr);
    }
  }
  if (state.mode === "person") for (const row of view.receipts || []) {
    const tr = document.createElement("tr");
    for (const value of [row.businessId, row.accepted ? "已受理" : "未受理", row.reason, row.amount, row.timeMillis]) {
      const td = document.createElement("td"); td.textContent = value; tr.appendChild(td);
    }
    $("manufacturing-receipts").appendChild(tr);
  }
  $("manufacturing-audit").hidden = state.mode !== "admin" || !view.enabled;
  if (state.mode === "admin" && view.enabled) {
    for (const [key, label] of [["assets", "资产"], ["liabilities", "负债"], ["equity", "权益"], ["profit", "累计损益"]]) {
      const card = document.createElement("div"), title = document.createElement("span"), value = document.createElement("strong");
      title.textContent = label; value.textContent = view[key]; card.appendChild(title); card.appendChild(value);
      $("manufacturing-summary").appendChild(card);
    }
    for (const row of view.reports || []) {
      const tr = document.createElement("tr");
      for (const value of [row.businessId, row.accepted ? "已受理" : "未受理", row.reason, row.amount, row.timeMillis]) {
        const td = document.createElement("td"); td.textContent = value; tr.appendChild(td);
      }
      $("manufacturing-reports").appendChild(tr);
    }
  }
  const actions = view.actions || [];
  $("manufacturing-form").hidden = state.mode !== "person" || !view.enabled || !actions.length;
  if (state.mode === "person" && actions.length) {
    const selected = $("manufacturing-action").value;
    $("manufacturing-action").replaceChildren(...actions.map(value => new Option(MANUFACTURING_ACTIONS[value] || value, value)));
    if (actions.includes(selected)) $("manufacturing-action").value = selected;
    const source = $("manufacturing-source").value;
    $("manufacturing-source").replaceChildren(...(view.funds || []).map(fund => new Option(fund.label, fund.token)));
    if ((view.funds || []).some(fund => fund.token === source)) $("manufacturing-source").value = source;
    if (!$("manufacturing-due").value) $("manufacturing-due").value = (BigInt(view.timeMillis) + 2000n).toString();
    manufacturingFields();
  }
}

function manufacturingFields() {
  const action = $("manufacturing-action").value;
  for (const field of document.querySelectorAll("[data-manufacturing-actions]")) {
    field.hidden = !field.dataset.manufacturingActions.split(" ").includes(action);
    for (const input of field.querySelectorAll("input,select")) input.disabled = field.hidden;
  }
}

async function submitManufacturing(event) {
  event.preventDefault();
  const generation = state.generation, view = state.manufacturingView;
  if (state.mode !== "person" || !view?.enabled) return;
  const action = $("manufacturing-action").value, id = "web-mfg-" + crypto.randomUUID();
  const command = { expectedPosition: view.position, commandId: id, taskId: id + "-task", action,
    dueTimeMillis: researchInteger("manufacturing-due"), durationMillis: researchInteger("manufacturing-duration"),
    businessId: action === "PAY_BILL" ? "" : $("manufacturing-business").value.trim(), resultId: "", quantity: "0",
    unitPrice: "0", promisedDeliveryAt: "0", period: "0", fundSource: "" };
  if (["SIGN", "PURCHASE_ORDER", "PRODUCTION", "DELIVERY"].includes(action)) command.quantity = researchInteger("manufacturing-quantity");
  if (action === "SIGN") {
    command.unitPrice = $("manufacturing-price").value.trim();
    command.promisedDeliveryAt = researchInteger("manufacturing-promised");
  }
  if (action === "DELIVERY") command.resultId = $("manufacturing-delivery-id").value.trim();
  if (action === "PAY_BILL") command.period = researchInteger("manufacturing-period");
  if (["PURCHASE_ORDER", "PRODUCTION", "PAY_BILL"].includes(action)) command.fundSource = $("manufacturing-source").value;
  $("manufacturing-submit").disabled = true;
  try {
    const result = await request("POST", "/api/me/manufacturing/action", command);
    if (!isCurrent(generation) || state.mode !== "person") return;
    if ([401, 403].includes(result.status)) { sessionLost(); return; }
    $("manufacturing-result").textContent = result.status === 200
      ? "已排程，等待世界推进；回执：" + result.payload.receipt
      : "未受理：" + (result.payload?.message || "请刷新后重试");
    await loadManufacturing(generation, null);
  } finally {
    if (isCurrent(generation) && state.mode === "person") $("manufacturing-submit").disabled = false;
  }
}

document.addEventListener("DOMContentLoaded", () => {
  $("manufacturing-action").addEventListener("change", manufacturingFields);
  $("manufacturing-form").addEventListener("submit", event => {
    const generation = state.generation;
    submitManufacturing(event).catch(failure => {
      if (isCurrent(generation) && state.mode === "person") $("manufacturing-result").textContent = failure.message || "提交失败";
    });
  });
});
