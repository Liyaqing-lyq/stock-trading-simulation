(() => {
  const labels = {PERSON:"个人", ORGANIZATION:"组织", PRODUCT:"产品", AGGREGATE:"聚合主体"};
  const priceSources = {TRADE:"实际成交", OPENING_REFERENCE:"开局参考估值", EX_DIVIDEND_REFERENCE:"除息参考估值", MISSING:"缺少估值"};
  const sessions = {OPEN:"交易中", PREOPEN:"开市前", LUNCH_BREAK:"午休", AFTER_CLOSE:"已收市", PERMANENTLY_CLOSED:"已关闭", CLOSED:"休市", WEEKEND:"周末",
    SECURITY_HALTED:"停牌", ISSUER_RESOLUTION_HALTED:"发行人处置停牌"};
  const shareClasses = {FLOAT:"流通股", LOCKED:"锁定股", TREASURY:"库存股"};
  const zero = {quantity:"0",valueDelta:"0.00",lots:[]};
  const el = id => document.getElementById(id);
  let view = null;
  const openGroups = new Set();

  function append(parent, tag, content) {
    const item = document.createElement(tag);
    if (content != null) item.textContent = content;
    parent.append(item);
    return item;
  }
  function clearEvidence() {
    el("exposure-evidence").hidden = true;
    el("exposure-evidence-title").textContent = "";
    el("exposure-quote").textContent = "";
    el("exposure-lots").replaceChildren();
  }
  function clear() {
    view = null;
    openGroups.clear();
    clearEvidence();
    el("exposure-matrix").replaceChildren();
    el("exposure-symbol").replaceChildren();
    el("exposure-cut").textContent = "";
  }
  function reference(issuer) {
    const mark = issuer.price === null ? "参考价未知"
      : issuer.price + " 元，价源时点 " + issuer.priceTime + " 毫秒，距本次截止 " + issuer.priceAge + " 毫秒";
    return issuer.symbol + " · " + issuer.name + "（" + issuer.issuerId + "），发行 "
      + issuer.issuedShares + " 股；" + (sessions[issuer.marketStatus] || issuer.marketStatus) + "，"
      + priceSources[issuer.source] + "，" + mark;
  }
  function receipt(lot) {
    const r = lot.original;
    if (r.kind === "TRADE") return "成交 " + r.tradeId + "，买单 " + r.buyOrderId + " / 卖单 "
      + r.sellOrderId + "，" + r.seller + " → " + r.buyer + "，" + r.quantity + " 股 × " + r.price + " 元，时点 " + r.timeMillis;
    if (r.kind === "OPENING_ALLOCATION") return "开局股份登记：" + r.accountId + "，" + r.symbol + "，"
      + r.quantity + " 股，成本 " + r.price + " 元，时点 " + r.timeMillis;
    return "证券子账原始批次 " + lot.lotId + "，取得时点 " + lot.acquiredAt + "，成本 " + lot.unitCost + " 元";
  }
  function showEvidence(group, owner, issuer) {
    clearEvidence();
    el("exposure-evidence").hidden = false;
    const target = owner || group;
    const cell = target.cells[issuer.symbol] || zero;
    const label = owner ? owner.name + "（" + owner.ownerId + "）" : labels[group.kind];
    el("exposure-evidence-title").textContent = label + " · " + issuer.symbol + " 持仓依据";
    el("exposure-quote").textContent = reference(issuer) + "。当前共 " + cell.quantity + " 股；条件市值变动 "
      + (cell.valueDelta === null ? "未知（有持仓但缺价）" : cell.valueDelta + " 元") + "。";
    let rows = 0;
    for (const holder of owner ? [owner] : group.owners) {
      for (const lot of holder.cells[issuer.symbol]?.lots || []) {
        const row = append(el("exposure-lots"), "tr");
        for (const value of [holder.ownerId + " / " + lot.accountId, lot.lotId, lot.quantity, shareClasses[lot.shareClass] || lot.shareClass, receipt(lot)])
          append(row, "td", value);
        rows++;
      }
    }
    if (!rows) append(append(el("exposure-lots"), "tr"), "td", "此截止没有持仓批次。").colSpan = 5;
  }
  function paint() {
    clearEvidence();
    const table = el("exposure-matrix");
    table.replaceChildren();
    if (!view) return;
    const selected = el("exposure-symbol").value;
    const issuers = view.issuers.filter(i => !selected || i.symbol === selected);
    const head = append(append(table,"thead"),"tr");
    append(head,"th","登记财产主体");
    for (const issuer of issuers) append(head,"th",issuer.symbol + " · " + issuer.name);
    if (!selected) append(head,"th","全部证券合计（元）");
    const body = append(table,"tbody");
    for (const group of view.groups) {
      function renderRow(owner) {
        const target = owner || group;
        const tr = append(body,"tr");
        const key = append(tr,"th");
        key.scope = "row";
        if (owner) key.textContent = owner.name === owner.ownerId ? owner.ownerId : owner.name + "（" + owner.ownerId + "）";
        else {
          const toggle = append(key,"button",labels[group.kind] + "（" + group.owners.length + "）");
          toggle.type = "button";
          toggle.setAttribute("aria-expanded",String(openGroups.has(group.kind)));
          toggle.addEventListener("click",() => {
            if (openGroups.has(group.kind)) openGroups.delete(group.kind); else openGroups.add(group.kind);
            paint();
          });
        }
        for (const issuer of issuers) {
          const cell = target.cells[issuer.symbol] || zero;
          const button = append(append(tr,"td"),"button",cell.valueDelta === null ? "未知" : cell.valueDelta);
          button.type = "button";
          button.title = cell.quantity + " 股；展开持仓依据";
          button.addEventListener("click",() => showEvidence(group,owner,issuer));
        }
        if (!selected) append(tr,"td",target.valueDelta === null ? "未知（含缺价持仓）" : target.valueDelta);
      }
      renderRow(null);
      if (openGroups.has(group.kind)) group.owners.forEach(renderRow);
    }
  }
  function render(result) {
    const oldSelection = el("exposure-symbol").value;
    view = result?.available ? result : null;
    el("exposure-symbol").replaceChildren();
    if (!view) {
      clear();
      el("exposure-cut").textContent = result ? "此世界没有可核对的证券持仓。" : "关系数据读取失败，请刷新。";
      return;
    }
    append(el("exposure-symbol"),"option","全部上市证券").value = "";
    for (const issuer of view.issuers) append(el("exposure-symbol"),"option",issuer.symbol + " · " + issuer.name).value = issuer.symbol;
    if (view.issuers.some(i => i.symbol === oldSelection)) el("exposure-symbol").value = oldSelection;
    el("exposure-cut").textContent = "确认记录 " + view.position + "，世界时点 " + view.timeMillis + " 毫秒。表内金额单位为元。";
    paint();
  }
  el("exposure-symbol").addEventListener("change",paint);
  globalThis.WorldExposure = {render,clear};
})();
