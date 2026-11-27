"use strict";

function clearInterviewCoordination() {
  const root = document.getElementById("person-interview-coordination");
  if (root) { root.replaceChildren(); root.hidden = true; }
}

function renderInterviewCoordination(view, generation, tools) {
  const root = document.getElementById("person-interview-coordination");
  const control = view.coordination;
  if (!root || !control) { clearInterviewCoordination(); return; }
  const inbound = view.invites.filter(row => row.toPartyId === view.personPartyId && row.status === "PROPOSED");
  if (!(control.inviteOffice || inbound.length || control.receipts.length)) { clearInterviewCoordination(); return; }
  root.hidden = false;
  if (root.dataset.actor !== view.personPartyId || !root.firstChild) {
    root.dataset.actor = view.personPartyId;
    root.innerHTML = `<section data-invite><h3>发起预约</h3><p data-clock class="hint"></p>
      <form class="form-grid"><label>已读联系入口<select name="target" required></select></label>
      <label>采访主题<input name="topic" required></label>
      <label>窗口开始（模拟毫秒）<input name="start" type="number" min="0" step="1" required></label>
      <label>窗口结束（模拟毫秒）<input name="end" type="number" min="1" step="1" required></label>
      <label>采访时长（毫秒）<input name="duration" type="number" min="1" step="1" required></label>
      <button type="submit">发送邀请</button></form></section>
      <section data-respond><h3>回应收到的预约</h3><form class="form-grid">
      <label>待回应的邀请<select name="invitation" required></select></label>
      <label>约定开始（模拟毫秒）<input name="when" type="number" min="0" step="1" required></label>
      <button type="submit" value="ACCEPT">接受</button><button type="submit" value="DECLINE" formnovalidate>拒绝</button></form></section>
      <p data-result role="status"></p><section data-receipts><h3>我的预约回执</h3><ul></ul></section>`;
  }
  function options(select, values) {
    const previous = select.value;
    select.replaceChildren(...values.map(([key, text]) => new Option(text, key)));
    if (values.some(([key]) => key === previous)) select.value = previous;
  }
  async function submit(payload, form) {
    const result = root.querySelector("[data-result]");
    const controls = Array.from(form.querySelectorAll("button")).filter(button => !button.disabled);
    controls.forEach(button => button.disabled = true);
    const contents = JSON.stringify([control.observedMillis, payload]);
    if (form.dataset.commandContents !== contents) {
      form.dataset.commandContents = contents;
      form.dataset.commandId = "web-interview-" + crypto.randomUUID();
    }
    const id = form.dataset.commandId;
    let refreshed = false;
    try {
      const answer = await request("POST", "/api/me/interview/coordination", {
        ...payload, commandId: id, observedMillis: String(control.observedMillis)
      });
      if (!isCurrent(generation) || state.mode !== "person") return;
      if (answer.status === 401) { sessionLost(); return; }
      result.textContent = answer.status === 200
        ? "已排程 " + answer.payload.receipt + "，执行时刻 " + answer.payload.dueTimeMillis + "。完成后可在本页查看回执。"
        : answer.payload?.message || "预约操作未排程，请刷新后核对。";
      if (answer.status === 200 || answer.status === 409) {
        await renderPersonalInterview(tools, generation);
        refreshed = true;
      }
    } catch (failure) {
      if (isCurrent(generation)) result.textContent = "请求中断，请核对最新回执。";
    } finally {
      if (isCurrent(generation) && !refreshed) controls.forEach(button => button.disabled = false);
    }
  }
  const invite = root.querySelector("[data-invite]");
  invite.hidden = !control.inviteOffice;
  if (!invite.hidden) {
    const form = invite.querySelector("form");
    form.querySelector("button").disabled = false;
    const e = form.elements;
    options(e.target, view.contacts.map(c => [c.targetPartyId, c.targetPartyId + " · " + c.channel]));
    form.hidden = !view.contacts.length;
    invite.querySelector("[data-clock]").textContent = view.contacts.length
      ? "本次邀请执行时刻 " + control.inviteDueMillis + "，最早可约 " + control.earliestWindowStartMillis + "（模拟毫秒）。"
      : "读完联系说明后，可选择对应的采访对象。";
    e.start.min = control.earliestWindowStartMillis;
    if (!e.start.value || Number(e.start.value) < Number(e.start.min)) e.start.value = e.start.min;
    form.onsubmit = ev => {
      ev.preventDefault();
      submit({action: "INVITE", toPartyId: e.target.value, topic: e.topic.value,
        windowStartMillis: e.start.value, windowEndMillis: e.end.value, durationMillis: e.duration.value}, form);
    };
  }
  const respond = root.querySelector("[data-respond]");
  respond.hidden = !inbound.length;
  if (inbound.length) {
    const form = respond.querySelector("form");
    form.querySelector('[value="DECLINE"]').disabled = false;
    const e = form.elements;
    options(e.invitation, inbound.map(i => [i.requestId, i.fromPartyId + " · " + i.topic]));
    function setWindow() {
      const i = inbound.find(r => r.requestId === e.invitation.value);
      const lo = Math.max(control.respondDueMillis, i.windowStartMillis);
      const hi = i.windowEndMillis - i.durationMillis;
      e.when.min = lo; e.when.max = hi;
      if (!e.when.value || Number(e.when.value) < lo || Number(e.when.value) > hi) e.when.value = lo;
      form.querySelector('[value="ACCEPT"]').disabled = lo > hi || control.respondDueMillis >= i.expiresAtMillis;
    }
    e.invitation.onchange = setWindow;
    setWindow();
    form.onsubmit = ev => {
      ev.preventDefault();
      const action = ev.submitter?.value;
      if (action !== "DECLINE" && action !== "ACCEPT") return;
      const payload = {action, requestId: e.invitation.value};
      if (action === "ACCEPT") payload.startMillis = e.when.value;
      submit(payload, form);
    };
  }
  const receipts = root.querySelector("[data-receipts]");
  receipts.hidden = !control.receipts.length;
  receipts.querySelector("ul").replaceChildren(...control.receipts.map(r => {
    const item = document.createElement("li");
    item.textContent = r.timeMillis + " · " + r.requestId + " · " + (r.accepted ? "已生效" : "未生效") + " · " + r.reason;
    if (r.windowTiming) {
      const t = r.windowTiming;
      item.textContent += " · 执行 " + t.executionMillis + "，所选 " + t.requestedStartMillis
        + "，可选开始范围 " + t.earliestStartMillis + " 至 " + t.latestStartMillis + "。"
        + t.reasonDetail;
    }
    return item;
  }));
}
