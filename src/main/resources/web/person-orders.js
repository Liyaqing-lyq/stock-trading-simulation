"use strict";

globalThis.PersonOrders = (() => {
  let submission = null;
  let revision = 0;

  function clear() {
    revision++;
    submission = null;
    $("person-stock-orders").hidden = true;
    $("stock-limit-form").reset();
    $("stock-order-result").textContent = "";
    $("stock-open-orders").replaceChildren();
    $("stock-order-receipts").replaceChildren();
    $("stock-order-account").replaceChildren();
    $("stock-order-symbol").replaceChildren();
  }

  function options(select, values) {
    const selected = select.value;
    select.replaceChildren(...values.map(value => new Option(value, value)));
    if (values.includes(selected)) select.value = selected;
  }

  async function load(mode, generation) {
    const latest = ++revision;
    const current = () => latest === revision && isCurrent(generation) && state.mode === "person";
    if (!["TAKEOVER", "READ_ONLY"].includes(mode)) return;
    let result;
    try { result = await request("GET", "/api/me/orders"); }
    catch (error) {
      if (current()) $("stock-order-result").textContent = "未能刷新本人委托。";
      return;
    }
    if (!current()) return;
    if (result.status !== 200 || !result.payload?.enabled) { clear(); return; }
    const view = result.payload, acting = mode === "TAKEOVER";
    $("person-stock-orders").hidden = false;
    const form = $("stock-limit-form");
    options($("stock-order-account"), view.accounts);
    options($("stock-order-symbol"), view.symbols);
    form.hidden = !acting || !view.accounts.length || !view.symbols.length;

    async function submit(fields, button) {
      if (!acting || !current() || button.disabled) return;
      const body = {...fields, observedMillis: String(view.observedMillis)};
      const content = JSON.stringify(body);
      if (submission?.content !== content) submission = {content, commandId: "web-stock-" + crypto.randomUUID()};
      button.disabled = true;
      try {
        const response = await request("POST", "/api/me/orders", {...body, commandId: submission.commandId});
        if (!current()) return;
        $("stock-order-result").textContent = response.status === 200
          ? "已排程 " + response.payload.receipt + "，执行时点 " + response.payload.dueTimeMillis + " 毫秒。"
          : response.payload?.message || "委托未排程。";
        if (response.status === 200 || response.status === 409) await load(mode, generation);
      } catch (error) {
        if (current()) $("stock-order-result").textContent = "请求中断，请刷新核对回执。";
      } finally {
        if (current()) button.disabled = false;
      }
    }

    const place = $("stock-place-limit");
    place.disabled = false;
    form.onsubmit = event => {
      event.preventDefault();
      void submit({action:"LIMIT", accountId:$("stock-order-account").value, symbol:$("stock-order-symbol").value,
        side:$("stock-order-side").value, limitPrice:$("stock-order-price").value.trim(),
        quantity:$("stock-order-quantity").value.trim()}, place);
    };
    const open = $("stock-open-orders");
    open.replaceChildren();
    for (const row of view.openOrders) {
      const tr = document.createElement("tr");
      for (const value of [row.accountId, row.orderRef, row.symbol, row.side === "BUY" ? "买入" : "卖出",
        row.limitPrice, row.quantity, row.filled, row.remaining]) cell(tr, value);
      const controls = document.createElement("td");
      if (acting) {
        const cancel = document.createElement("button");
        cancel.type = "button"; cancel.textContent = "撤销委托"; cancel.setAttribute("aria-label", "撤销委托 " + row.orderRef);
        cancel.onclick = () => void submit({action:"CANCEL", accountId:row.accountId, orderRef:row.orderRef}, cancel);
        controls.appendChild(cancel);
      }
      tr.appendChild(controls);open.appendChild(tr);
    }
    $("stock-open-empty").hidden = view.openOrders.length > 0;
    const receipts = $("stock-order-receipts");
    receipts.replaceChildren();
    for (const row of view.receipts) {
      const tr = document.createElement("tr");
      for (const value of [row.timeMillis, row.action === "LIMIT" ? "限价申报" : "撤销",
        row.commandId, row.accepted ? "已受理" : "已拒绝", row.reason, row.orderRef, row.status,
        row.filled, row.remaining]) cell(tr, value);
      receipts.appendChild(tr);
    }
    $("stock-receipts-empty").hidden = view.receipts.length > 0;
  }
  return {clear, load};
})();
