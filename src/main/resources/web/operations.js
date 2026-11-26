"use strict";
const OPERATIONS_ACTIONS = {
  BEGIN_PILOT:["开始试点",[]], BEGIN_VALIDATION:["开始客户验证",[]], APPROVE_COMMERCIAL:["确认客户商用准入",["approvalId"]], TERMINATE:["终止未交付合同",["contractId"]], REFUND:["退还预收",["contractId","refundCents"]],
  ORDER_RAW:["订购原料",["orderId","units"]],RECEIVE_RAW:["接收并付款",["orderId"]],PRODUCE:["投入陈酿生产",["batchId","units"]],SET_PRICE:["设置渠道报价",["channelId","priceCents"]],SHIP:["发往寄售渠道",["shipmentId","channelId","units"]],PAY_REFUND:["支付渠道退款",["channelId","amountCents"]],PAY_FIXED:["支付欠付固定费",["amountCents"]],
  DISPATCH_FLOW: ["调整过机流量", ["cubicMetresPerHour"]], DISPATCH_OUTPUT: ["调整发电出力", ["outputKw"]], MAINTENANCE: ["按报价检修", []],
  EXIT_BUSINESS: ["按董事会决议停止生产",["meetingId"]], SETTLE_BILL: ["支付原期间欠费",["period"]],
  SUBSCRIBE_SHARES: ["用本人现金认购普通股",["meetingId","shares"]],
  BEGIN_EXTRACTION: ["安排开采", ["jobId", "seamId", "quantityKg"]],
  SELL_COAL: ["交付外售煤炭", ["deliveryId", "quantityKg", "receivableId"]],
  TRANSFER_TO_PLANT: ["煤炭转入电厂", ["transferId", "quantityKg"]],
  BEGIN_POWER: ["安排发电交付", ["jobId", "mwh", "receivableId"]],
  ABORT_JOB: ["终止进行中作业", ["jobId"]], WRITE_OFF: ["核销资源应收", ["receivableId"]],
  ORDER_MATERIAL: ["采购材料", ["materialId", "quantity"]],
  START_BATCH: ["安排生产", ["productId", "generation", "quantity"]],
  REQUEST_CERT: ["申请客户认证", ["trialId", "evidenceId", "customerId", "productId", "generation", "round"]],
  SIGN_CONTRACT: ["签订销售合同", ["contractId", "customerId", "productId", "generation", "quantity", "unitPrice", "promisedAt", "expiresAt"]],
  DELIVER: ["交付产品", ["contractId", "quantity", "receivableId"]],
  COLLECT: ["收取到期货款", ["receivableId"]],
  MARK_OVERDUE: ["标记合同逾期", ["contractId"]],
  CANCEL_CONTRACT: ["取消未交合同", ["contractId"]],
  WRITE_OFF_RECEIVABLE: ["核销坏账", ["receivableId"]],
  START_EXPANSION: ["按报价扩产", ["projectId", "capex", "buildMillis", "rampPeriods", "addCapacity"]],
  SELL_RESIDUAL: ["出售残余资产", ["projectId", "price"]]
};
const OPERATIONS_LABELS = { approvalId:"客户技术准入编号", quantityUnits:"服务合同数量", prepaidCents:"预收金额（元）", units:"数量", refundCents:"退款金额（元）", amountCents:"支付金额（元）", priceCents:"渠道单价（元）", orderId:"采购单编号", batchId:"生产批次编号", channelId:"渠道编号", shipmentId:"发货单编号", cubicMetresPerHour:"过机流量（立方米/小时）", outputKw:"出力（千瓦）", amount:"回款金额（元）", jobId: "本次作业编号", seamId: "矿脉编号", quantityKg: "煤炭数量（千克）", deliveryId: "本次交付编号", transferId: "本次转运编号", mwh: "发电量（兆瓦时）", materialId: "材料编号", quantity: "数量", productId: "产品编号", generation: "产品代际",
  trialId: "本次认证编号", evidenceId: "已完成的阅读编号", customerId: "客户编号", round: "认证轮次",
  contractId: "合同编号", unitPrice: "销售单价（元）", promisedAt: "承诺交付时点", expiresAt: "合同截止时点",
  receivableId: "应收款编号", projectId: "扩产项目编号", capex: "承包报价（元）", buildMillis: "建设耗时",
  rampPeriods: "爬坡期数", addCapacity: "新增产能", price: "出售价格（元）" };

function clearOperationsDom() {
  state.operationsView = null;
  renderIntake({ companies: [] });
  renderUpgrade({ companies: [] });
  renderLoanPromotion(null);
  state.operationsRequestedCompany = "";
  for (const id of ["operations-company", "operations-action", "operations-fields", "operations-financial", "operations-records"]) {
    if ($(id)) $(id).replaceChildren();
  }
  for (const id of ["operations-note", "operations-cut", "operations-result"]) if ($(id)) $(id).textContent = "";
  if ($("operations-due")) $("operations-due").value = "";
  if ($("operations-form")) $("operations-form").hidden = true;
}

async function loadOperations(generation, pageVersion) {
  const mode = state.mode, version = pageVersion ?? state.pageVersion;
  const current = () => isCurrent(generation) && state.mode === mode && state.pageVersion === version
    && (mode === "person" || state.page === "operations");
  try {
    const response = await request("GET", mode === "admin" ? "/api/admin/operations" : "/api/me/operations");
    if (!current()) return;
    if (response.status === 401 || response.status === 403) { sessionLost(); return; }
    if (response.status !== 200) throw new Error(response.payload?.message || "经营资料暂时无法读取");
    renderOperations(response.payload);
  } catch (error) {
    if (!current()) return;
    clearOperationsDom(); $("operations-note").textContent = error.message;
  }
}

function operationsLine(parent, text) {
  const element = document.createElement("p"); element.textContent = text; parent.appendChild(element);
}
function operationsOption(label, value) {
  const option = document.createElement("option"); option.textContent = label; option.value = value; return option;
}
function operationsCompany() {
  return state.operationsView?.companies?.find(c => c.companyId === $("operations-company").value);
}
function renderOperations(view) {
  const requested = state.operationsRequestedCompany;
  const selected = requested || $("operations-company").value;
  state.operationsRequestedCompany = "";
  state.operationsView = view;
  const panel = document.querySelector('[data-panel="operations"]');
  const host = state.mode === "person" ? $("person") : $("admin");
  if (panel && host && panel.parentElement !== host) host.appendChild(panel);
  if (panel) panel.hidden = state.mode === "person" ? !view.enabled : state.page !== "operations";
  $("operations-note").textContent = view.note || "";
  $("operations-cut").textContent = (view.position == null ? "" : "记录 " + view.position + " · ") + "世界时点 " + view.timeMillis + " 毫秒";
  const companies = view.companies || [];
  const select = $("operations-company");
  select.replaceChildren(...companies.map(c => operationsOption(c.name, c.companyId)));
  const wanted = companies.find(c => c.companyId === selected);
  select.value = wanted?.companyId || (requested ? "" : companies[0]?.companyId || "");
  renderOperationsCompany();
  renderIntake(view);
  renderUpgrade(view);
  renderLoanPromotion(view.loanPromotion);
}
function renderLoanPromotion(loan) {
  const section = $("operations-loan-promotion");
  const visible = state.mode === "admin" && Boolean(loan);
  section.hidden = !visible;
  $("loan-promotion-record").textContent = visible
    ? "合同 " + loan.loanId + " · 银行 " + loan.bankId + " · 借款人 " + loan.borrower
      + " · 名义本金 " + loan.nominal + " 元 · 表内本金 " + loan.onBook + " 元 · " + loan.allowanceBucket + " 风险桶减值准备 " + loan.allowance + " 元"
      + " · " + (loan.status === "DETAILED" ? "已列借款人明细" : "仍列汇总敞口") : "";
  $("loan-promotion-source").textContent = visible ? "原始记录 " + loan.sourcePosition + " · 凭证摘要 " + loan.sourceDigest : "";
  $("loan-promotion-apply").disabled = !visible || loan.status !== "AGGREGATE";
  $("loan-promotion-result").textContent = "";
}
function renderUpgrade(view) {
  const section = $("operations-upgrade"), choices = $("upgrade-companies"), batches = $("upgrade-batches");
  section.hidden = state.mode !== "admin" || !view.manufacturingUpgrade;
  choices.querySelectorAll("label").forEach(label => label.remove());
  batches.replaceChildren();
  $("upgrade-result").textContent = "";
  $("upgrade-prepare").disabled = true;
  state.upgradeDraft = null;
  if (section.hidden) return;
  const rules = view.manufacturingUpgrade;
  for (const company of view.companies.filter(c => c.templateVersion)) {
    const label = document.createElement("label"), input = document.createElement("input");
    input.type = "checkbox"; input.value = company.companyId;
    input.disabled = company.templateVersion !== rules.fromVersion;
    label.append(input, company.name + (input.disabled ? "（已变更）" : ""));
    choices.appendChild(label);
  }
  for (const batch of rules.batches) {
    const line = document.createElement("p"), draft = batch.draft;
    const applied = String(batch.appliedPosition) !== "0";
    line.textContent = draft.companyIds.join("、") + " · " + (applied ? "已变更 · 记录 " + batch.appliedPosition : "已登记，等待应用");
    if (!applied) {
      const button = document.createElement("button");
      button.type = "button"; button.textContent = "应用此批变更";
      button.onclick = () => guard(submitUpgrade(view,"APPLY",draft));
      line.appendChild(button);
    }
    batches.appendChild(line);
  }
}
async function submitUpgrade(view, stage, draft) {
  const generation = state.generation;
  const active = () => isCurrent(generation) && state.mode === "admin" && state.page === "operations";
  if (!active() || view !== state.operationsView || !draft) return;
  const response = await request("POST","/api/admin/operations/manufacturing-upgrade",{
    expectedPosition:view.position,stage,...draft
  });
  if (!active() || view !== state.operationsView) return;
  if (response.status === 401 || response.status === 403) { sessionLost(); return; }
  if (response.status !== 200) { $("upgrade-result").textContent = response.payload?.message || "变更未完成，请刷新核对。"; return; }
  const result = response.payload;
  if (stage === "PREVIEW" && state.upgradeDraft !== draft) return;
  if (stage === "PREVIEW") $("upgrade-prepare").disabled = false;
  else await loadOperations(generation,state.pageVersion);
  if (!active()) return;
  const phases = { VALIDATED:"已核对所选公司。", WAITING_SAFE_POINT:"仍有采购在途，须等待完结。", READY:"已登记，可应用此批变更。", APPLIED:"变更已生效。" };
  $("upgrade-result").textContent = phases[result.phase] + (result.blockers.length ? " " + result.blockers.join("、") : "");
}
async function submitLoanPromotion() {
  const view = state.operationsView;
  if (state.mode !== "admin" || view?.loanPromotion?.status !== "AGGREGATE") return;
  const generation = state.generation, version = state.pageVersion;
  $("loan-promotion-apply").disabled = true;
  try {
    const response = await request("POST", "/api/admin/operations/loan-promotion", {
      loanId: view.loanPromotion.loanId, expectedPosition: view.position
    });
    if (!isCurrent(generation) || state.mode !== "admin" || state.pageVersion !== version) return;
    if (response.status !== 200) {
      $("loan-promotion-result").textContent = response.payload?.message || "登记未完成，请刷新后核对。";
      return;
    }
    await loadOperations(generation, version);
  } catch (error) {
    if (isCurrent(generation) && state.mode === "admin" && state.pageVersion === version)
      $("loan-promotion-result").textContent = error.message;
  }
}
function renderIntake(view) {
  const section = $("operations-intake");
  section.hidden = state.mode !== "admin" || !view.intakeLifecycle;
  const fieldset = $("intake-companies");
  fieldset.querySelectorAll("label").forEach(label => label.remove());
  $("intake-apply").disabled = true;
  $("intake-result").textContent = "";
  if (section.hidden) return;
  for (const company of view.companies.filter(c => c.intakeVersion)) {
    const label = document.createElement("label"), input = document.createElement("input");
    input.type = "checkbox"; input.value = company.companyId;
    input.disabled = company.intakeVersion !== view.intakeLifecycle.fromVersion;
    label.append(input, company.name + (input.disabled ? "（已停止新签）" : ""));
    fieldset.append(label);
  }
}
async function submitIntake(apply) {
  const view = state.operationsView, generation = state.generation, fieldset = $("intake-companies");
  const companies = () => Array.from(fieldset.querySelectorAll("input:checked"), input => input.value);
  const ids = companies();
  if (state.mode !== "admin" || !view?.intakeLifecycle || !ids.length) return;
  $("intake-apply").disabled = true;
  const body = { ...view.intakeLifecycle, expectedPosition: view.position, companyIds: ids, apply };
  const result = await request("POST", "/api/admin/operations/intake", body);
  if (!isCurrent(generation) || state.mode !== "admin" || state.operationsView !== view) return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200) { $("intake-result").textContent = result.payload?.message || "操作失败"; return; }
  if (apply) {
    await loadOperations(generation, null);
    if (isCurrent(generation) && state.mode === "admin") $("intake-result").textContent = "已停止所选公司的新签业务。记录 " + result.payload.position;
  } else if (JSON.stringify(ids) === JSON.stringify(companies())) {
    $("intake-apply").disabled = false;
    $("intake-result").textContent = "已校验 " + ids.length + " 家公司，请核对后确认。";
  }
}
function operationsTable(parent, title, rows, columns) {
  const details = document.createElement("details"), summary = document.createElement("summary");
  summary.textContent = title + " · " + rows.length; details.appendChild(summary);
  const wrap = document.createElement("div"); wrap.className = "table-wrap";
  const table = document.createElement("table"), head = document.createElement("thead"), tr = document.createElement("tr");
  for (const [, label] of columns) { const th = document.createElement("th"); th.textContent = label; tr.appendChild(th); }
  head.appendChild(tr); table.appendChild(head);
  const body = document.createElement("tbody");
  for (const row of rows) { const line = document.createElement("tr"); for (const [key] of columns) {
    const cell = document.createElement("td"); const statuses = { FULFILLED:"已履约", COLLECTED:"已收款", FINISHED:"已完工", ORDERED:"待到货", RECEIVED:"已到货", FAILED_NO_CASH:"资金不足", IN_PROGRESS:"进行中", COMPLETED:"已完成", RUNNING:"进行中", ABORTED:"已终止", BUILDING:"建设中", ONLINE:"已投产", ACTIVE:"有效", PASSED:"通过", FAILED:"未通过", DELIVERED:"已交付", PAID:"已回款", WRITTEN_OFF:"已核销", CANCELLED:"已取消", OVERDUE:"已逾期" };
    const value = row[key];
    if (key === "status" && value === "OPEN" && row.receivableId) statuses.OPEN = "待收款";
    cell.textContent = key === "status" ? (statuses[value] || String(value ?? "—")) : key === "accepted" ? (value ? "是" : "否") : key === "action" ? (OPERATIONS_ACTIONS[value]?.[0] || String(value ?? "—")) : String(value ?? "—"); line.appendChild(cell);
  } body.appendChild(line); }
  table.appendChild(body); wrap.appendChild(table); details.appendChild(wrap); parent.appendChild(details);
}
function renderOperationsCompany() {
  const company = operationsCompany(), financial = $("operations-financial"), records = $("operations-records");
  financial.replaceChildren(); records.replaceChildren();
  if (!company) { $("operations-form").hidden = true; return; }
  if (company.templateVersion) operationsLine(financial,"采购模板 " + company.templateVersion + " · 新单交期 " + Number(company.newProcurementLeadMillis)/86400000 + " 天");
  if (company.primaryOffer) {
    operationsLine(financial,company.primaryOffer.terms);
    operationsLine(financial,"有效决议 " + company.primaryOffer.meetingIds.join("、") + "，剩余 " + company.primaryOffer.remainingShares + " 股。");
  }
  if (company.financial) {
  operationsLine(financial, company.name + " · 完整公司报表");
  for (const [key, label] of [["assets", "资产"], ["liabilities", "负债"], ["equity", "权益"]]) {
    operationsLine(financial, label + "：" + Object.entries(company.financial[key] || {}).map(([name, amount]) => name + " " + amount + " 元").join("；"));
  }
  const entries = object => Object.entries(object || {}).map(([id, row]) => ({ id, ...row }));
  if (company.kind === "DIGITAL" || company.kind === "AGED") {
    const plan=company.plan;
    operationsLine(financial,"本期生产预算 " + (plan.remainingBudget == null ? "未批准" : plan.remainingBudget+" 元") + "；现金、应收与负债见完整报表。");
    operationsLine(records,company.kind === "DIGITAL" ? "试点计入费用，客户验证投入先记在研。商用准入不产生收入；实际交付才确认收入，未赚预收仍需退款。" : "采购到货才付款，生产后须等待成熟。发货只是寄售；终端购买才产生收入，渠道回款另行记录。到货期末仍未付款的采购会失效并保留记录。");
    if(company.kind === "DIGITAL") {
      operationsLine(records,"客户商用准入 " + (plan.commercialApproved ? "已确认":"未确认") + "；准入编号 " + (plan.commercialPermit || "—") + "；服务容量 " + plan.serviceCapacityUnitsPerPeriod + " /期。");
      operationsTable(records,"实际试验阶段",entries(plan.stages),[["id","阶段"],["status","状态"],["beganAt","开始时点"],["completesAt","完成时点"]]);
      operationsTable(records,"服务合同",entries(plan.contracts),[["id","合同"],["status","状态"],["quantity","数量"],["delivered","已交付"]]);
      operationsTable(records,"应收服务款",entries(plan.receivables),[["id","编号"],["amount","金额"],["dueAt","到期时点"],["status","状态"]]);
    } else {
      operationsLine(records,"原料 " + plan.rawUnitCost + " 元/单位；转产 " + plan.conversionPerUnit + " 元/单位；成熟需 " + plan.agingPeriods + " 期。发货只是寄售，终端售出才确认收入。");
      operationsTable(records,"渠道规则",plan.channels || [],[["channelId","渠道"],["quotaPerPeriod","每期额度"],["openingPrice","开局价"]]);
      operationsTable(records,"采购单",plan.orders || [],[["orderId","采购单"],["units","数量"],["duePeriod","到货期"]]);
      operationsTable(records,"真实批次",plan.batches || [],[["batchId","批次"],["remaining","剩余"],["matureAtPeriod","成熟期"]]);
      operationsTable(records,"寄售未售",plan.shipmentLots || [],[["shipmentId","发货单"],["channelId","渠道"],["remaining","剩余"]]);
    }
  } else if (company.kind === "HYDRO") {
    const plan=company.plan, plant=plan.plant;
    operationsLine(financial,"物理账截至 " + plan.physicalAsOf + " 毫秒；检修预算 " + (plan.remainingBudget == null ? "未批准" : plan.remainingBudget + " 元"));
    operationsLine(records,"库水 " + plant.storageCubicMetres + " 立方米，累计来水 " + plant.inflowCubicMetres + "，强制下泄 " + plant.mandatoryReleaseCubicMetres + "，溢洪 " + plant.overflowSpillCubicMetres + "。");
    operationsLine(records,"累计发电 " + plant.generatedKilowattHours + " 千瓦时，交付 " + plant.deliveredKilowattHours + " 千瓦时，实际回款 " + plant.collectedCash + " 元。");
    operationsLine(records,"检修报价 " + plan.maintenanceOffer.cost + " 元、停机 " + plan.maintenanceOffer.durationMillis + " 毫秒。所有调度需15分钟工作，未来来水不属于经理已知信息。");
  } else if (company.kind === "RESOURCE") {
    const plan = company.plan;
    if (plan.cessation) operationsLine(records,"已停止生产，董事决议 " + plan.cessation.meetingId + "，确认于 " + plan.cessation.timeMillis + " 毫秒。");
    else if (plan.cessationDecisions?.length) operationsLine(records,"可执行的停业决议 " + plan.cessationDecisions.join("、"));
    if (plan.expenseBills) operationsTable(records,"原期间费用",Object.values(plan.expenseBills),[["period","期间"],["amount","应计金额（元）"],["paid","已付金额（元）"]]);
    operationsLine(financial, "本期基础能力：开采 " + plan.mineCapacityKg + " 千克，运输 " + plan.transportCapacityKg + " 千克，发电 " + plan.generatorCapacityMwh + " 兆瓦时；经营预算 " + (plan.remainingBudget == null ? "未批准" : plan.remainingBudget + " 元"));
    operationsLine(records, "矿端库存 " + plan.mineCoal.quantity + " 千克；厂内燃料 " + plan.plantFuel.quantity + " 千克。内部转运不确认外部收入。");
    operationsLine(records, "矿脉：" + (plan.seams || []).map(seam => seam.seamId + "，开采成本 " + seam.extractionCostPerKg + " 元/千克").join("；"));
    operationsTable(records, "开采与发电作业", entries(company.jobs), [["id", "作业"], ["kind", "类型"], ["quantityKg", "开采千克"], ["mwh", "发电兆瓦时"], ["status", "状态"], ["dueAt", "完成时点"]]);
    if (plan.expansionOffer) {
      operationsLine(records, "扩产报价 " + plan.expansionOffer.capex + " 元，建设 " + plan.expansionOffer.buildMillis + " 毫秒，完成后的完整期间逐步增加产能；处置价以已确认残值为准，受买方实际现金约束。");
      operationsTable(records, "实际扩产项目", entries(plan.expansions), [["id", "项目"], ["status", "状态"], ["completesAt", "完成时点"], ["capex", "投入金额"]]);
    }
    operationsTable(records, "实际外部应收", entries(company.receivables), [["id", "编号"], ["amount", "金额"], ["dueAt", "到期时点"], ["status", "状态"]]);
  } else {
  operationsLine(financial, "本期可用产能 " + company.plan.capacityThisPeriod + "；预算余额：" + Object.entries(company.plan.approvedBudgetRemaining || {}).map(([name, value]) => (name === "PROCUREMENT" ? "采购及经营" : "生产人工") + " " + (value == null ? "未批准" : value + " 元")).join("；"));
  operationsLine(records, "客户：" + (company.plan.customers || []).join("，"));
  operationsLine(records, "产品：" + (company.plan.products || []).map(p => p.productId + " · 第" + p.generation + "代").join("，"));
  operationsLine(records, "可见材料报价：" + (company.plan.visibleQuotes || []).map(q => q.materialId + " " + q.price + " 元").join("，"));
  operationsTable(records, "原材料库存", entries(company.materials), [["id", "材料"], ["quantity", "数量"], ["amount", "成本（元）"]]);
  operationsTable(records, "采购单", entries(company.purchases), [["id", "编号"], ["materialId", "材料"], ["quantity", "数量"], ["status", "状态"], ["arrivalAt", "到货时点"]]);
  operationsLine(records, "生产按产品和代际入库。交付凭证通过合同编号关联应收。");
  operationsTable(records, "生产批次（按产品入库）", entries(company.batches), [["id", "批次编号"], ["productId", "产品"], ["generation", "代际"], ["quantity", "数量"], ["startedAt", "开工时点"], ["completesAt", "完工时点"], ["status", "状态"]]);
  const contracts = entries(company.contracts).map(contract => {
    if (contract.status === "OVERDUE") return { ...contract, status: "已登记逾期" };
    if (contract.status === "ACTIVE" && /^\d+$/.test(contract.expiresAt)
        && BigInt(state.operationsView.timeMillis) > BigInt(contract.expiresAt)) {
      return { ...contract, status: "账载有效，已过最迟交付时点（待登记逾期）" };
    }
    return contract;
  });
  operationsTable(records, "销售合同", contracts, [["id", "合同编号"], ["customerId", "客户"], ["productId", "产品"], ["generation", "代际"], ["quantity", "签约数量"], ["unitPrice", "单价（元）"], ["signedAt", "签约时点"], ["promisedAt", "承诺交付时点"], ["expiresAt", "最迟交付时点"], ["delivered", "已交数量"], ["status", "状态"]]);
  operationsTable(records, "应收货款", entries(company.receivables), [["id", "应收编号"], ["contractId", "合同编号"], ["quantity", "交付数量"], ["recognizedAt", "交付确认时点"], ["amount", "确认金额（元）"], ["outstanding", "未收（元）"], ["dueAt", "到期时点"], ["status", "状态"]]);
  operationsTable(records, "认证与扩产", [...entries(company.trials), ...entries(company.expansions)], [["id", "编号"], ["status", "状态"], ["finishAt", "认证时点"], ["completesAt", "扩产时点"]]);
  }
  }
  const privateReceipts = state.operationsView.observationToken != null;
  const reports = (company.reports || []).slice();
  if (privateReceipts) reports.reverse();
  else reports.sort((a,b) => b.position-a.position || b.timeMillis-a.timeMillis);
  operationsTable(records, "实际执行回报", reports, [...(privateReceipts ? [] : [["position", "确认记录"]]), ["timeMillis", "执行时点"], ["action", "动作"], ["businessId", "业务单号"], ["accepted", "是否受理"], ["reason", "结果"], ["amount", "实际划款额（元）"]]);
  const actions = company.actions || [], selected = $("operations-action").value;
  $("operations-action").replaceChildren(...actions.filter(a => OPERATIONS_ACTIONS[a]).map(a => operationsOption(OPERATIONS_ACTIONS[a][0], a)));
  if (actions.includes(selected)) $("operations-action").value = selected;
  $("operations-form").hidden = state.mode !== "person" || actions.length === 0;
  $("operations-duration").value = company.kind === "HYDRO" ? "900000" : "1000";
  $("operations-due").value = (BigInt(state.operationsView.timeMillis) + BigInt($("operations-duration").value) + 2000n).toString();
  operationsFields();
}
function operationsFields() {
  const company = operationsCompany(), action = $("operations-action").value;
  $("operations-fields").replaceChildren();
  for (const name of (["RESOURCE","HYDRO","DIGITAL","AGED"].includes(company?.kind) ? company.plan?.actionFields?.[action]?.split(/,\s*/).filter(Boolean) : OPERATIONS_ACTIONS[action]?.[1]) || []) {
    const label = document.createElement("label"), input = document.createElement("input");
    label.textContent = OPERATIONS_LABELS[name] || name; input.id = "operations-field-" + name; input.required = true;
    if (company?.plan?.expansionOffer && ["capex", "buildMillis", "rampPeriods", "addCapacity"].includes(name)) {
      input.value = String(company.plan.expansionOffer[name]); input.readOnly = true;
    } else if (["quantity", "generation", "round"].includes(name)) input.value = "1";
    label.appendChild(input); $("operations-fields").appendChild(label);
  }
}
function operationsInputValue(name, value) {
  if (!name.endsWith("Cents")) return value;
  const match = /^(0|[1-9][0-9]*)(?:\.([0-9]{1,2}))?$/.exec(value);
  if (!match) throw new Error("金额请填写非负数，最多保留两位小数。");
  return (BigInt(match[1]) * 100n + BigInt((match[2] || "").padEnd(2,"0"))).toString();
}
async function submitOperations(event) {
  event.preventDefault();
  const generation = state.generation, company = operationsCompany(), view = state.operationsView;
  if (state.mode !== "person" || !company || !view) return;
  const action = $("operations-action").value, fields = {};
  for (const name of (["RESOURCE","HYDRO","DIGITAL","AGED"].includes(company?.kind) ? company.plan?.actionFields?.[action]?.split(/,\s*/).filter(Boolean) : OPERATIONS_ACTIONS[action]?.[1]) || []) fields[name] = operationsInputValue(name,$("operations-field-" + name).value.trim());
  const id = "web-ops-" + crypto.randomUUID();
  const command = { expectedPosition: view.observationToken ?? view.position, commandId: id, taskId: id + "-task", companyId: company.companyId,
    action, fields, dueTimeMillis: $("operations-due").value.trim(), durationMillis: $("operations-duration").value.trim() };
  $("operations-submit").disabled = true;
  try {
    const result = await request("POST", company.kind === "DIGITAL" ? "/api/me/digital/action" : company.kind === "AGED" ? "/api/me/aged/action" : company.kind === "HYDRO" ? "/api/me/hydro/action" : company.kind === "RESOURCE" ? "/api/me/resources/action" : "/api/me/operations/action", command);
    if (!isCurrent(generation) || state.mode !== "person") return;
    if (result.status === 401 || result.status === 403) { sessionLost(); return; }
    $("operations-result").textContent = result.status === 200 ? "已安排工作，推进世界后查看实际回报。" : "未受理：" + (result.payload?.message || "请刷新后重试");
    await loadOperations(generation, null);
  } finally { if (isCurrent(generation) && state.mode === "person") $("operations-submit").disabled = false; }
}
document.addEventListener("DOMContentLoaded", () => {
  $("upgrade-companies").addEventListener("change", () => {
    state.upgradeDraft = null; $("upgrade-prepare").disabled = true; $("upgrade-result").textContent = "";
  });
  $("upgrade-check").addEventListener("click", () => {
    const view = state.operationsView, rules = view?.manufacturingUpgrade;
    if (!rules) return;
    const companyIds = Array.from($("upgrade-companies").querySelectorAll("input:checked"), el => el.value);
    if (!companyIds.length) { $("upgrade-result").textContent = "请先选择制造公司。"; return; }
    state.upgradeDraft = { batchId:"web-" + crypto.randomUUID(),fromVersion:rules.fromVersion,toVersion:rules.toVersion,companyIds };
    guard(submitUpgrade(view,"PREVIEW",state.upgradeDraft));
  });
  $("upgrade-prepare").addEventListener("click", () => guard(submitUpgrade(state.operationsView,"PREPARE",state.upgradeDraft)));
  $("loan-promotion-apply").addEventListener("click", submitLoanPromotion);
  $("intake-companies").addEventListener("change", () => { $("intake-apply").disabled = true; $("intake-result").textContent = ""; });
  for (const id of ["intake-check", "intake-apply"]) $(id).addEventListener("click", () => {
    submitIntake(id === "intake-apply").catch(error => { if (state.mode === "admin") $("intake-result").textContent = error.message; });
  });
  $("operations-company").addEventListener("change", renderOperationsCompany);
  $("operations-action").addEventListener("change", operationsFields);
  $("operations-form").addEventListener("submit", event => {
    const generation = state.generation;
    submitOperations(event).catch(error => { if (isCurrent(generation)) $("operations-result").textContent = error.message; });
  });
});
