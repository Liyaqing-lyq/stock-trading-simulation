"use strict";

globalThis.Newsletters = (() => {
  let revision = 0;
  const pending = new Map();

  function clear() {
    revision++;
    pending.clear();
    $("person-newsletters").hidden = true;
    for (const id of ["newsletter-offers", "newsletter-releases", "newsletter-deliveries", "newsletter-result"])
      $(id).replaceChildren();
  }

  function button(label, click) {
    const control = document.createElement("button");
    control.type = "button";
    control.textContent = label;
    control.onclick = () => void click(control);
    return control;
  }

  async function load(mode, generation) {
    const stamp = ++revision;
    const current = () => stamp === revision && isCurrent(generation) && state.mode === "person";
    if (!["TAKEOVER", "READ_ONLY"].includes(mode)) return;
    const result = await request("GET", "/api/me/newsletters").catch(() => null);
    if (!current()) return;
    if (!result || result.status !== 200 || !result.payload?.enabled) { clear(); return; }
    const view = result.payload;
    const acting = mode === "TAKEOVER";
    const panel = $("person-newsletters"), feedback = $("newsletter-result");
    panel.hidden = false;
    for (const id of ["newsletter-offers", "newsletter-releases", "newsletter-deliveries"]) $(id).replaceChildren();

    async function perform(control, action, target, version) {
      if (!acting || !current() || control.disabled) return;
      const reading = action === "READ";
      const key = JSON.stringify([view.person, action, target, version, view.observedMillis]);
      if (!pending.has(key)) pending.set(key, crypto.randomUUID());
      const id = pending.get(key);
      control.disabled = true;
      try {
        const requestBody = reading
          ? {expectedPosition:view.observationToken, commandId:"news-read-" + id, taskId:"news-read-task-" + id,
              dueTimeMillis:String(BigInt(view.observedMillis) + 1n), docId:target, version:String(version)}
          : {action, target, observedMillis:view.observedMillis, commandId:id};
        const response = await request("POST", reading ? "/api/me/read" : "/api/me/newsletters", requestBody);
        if (!current()) return;
        feedback.textContent = response.status === 200
          ? "已排程：" + response.payload.receipt : response.payload?.message || "请求未获受理。";
        if (response.status === 200 || response.status === 409) await load(mode, generation);
      } catch {
        if (current()) feedback.textContent = "连接中断，请刷新并核对回执。";
      } finally {
        if (current()) control.disabled = false;
      }
    }

    for (const offer of view.offers || []) {
      const row = document.createElement("div");
      row.textContent = (offer.name || offer.organization) + (offer.subscribed ? " · 已订阅 " : " · 免费试刊 ");
      if (acting) row.append(button(offer.subscribed ? "退订" : "订阅",
        control => perform(control, offer.subscribed ? "LEAVE" : "JOIN", offer.organization)));
      $("newsletter-offers").append(row);
    }
    for (const turn of view.releasableTurns || []) {
      const row = document.createElement("p");
      row.textContent = turn.title + " ";
      if (acting) row.append(button("授权向订户刊载我的这段原话", control => perform(control, "RELEASE_TURN", turn.turnId)));
      $("newsletter-releases").append(row);
    }
    for (const item of view.deliveries || []) {
      const article = document.createElement("article"), title = document.createElement("h3");
      title.textContent = item.title;
      const details = document.createElement("p");
      details.textContent = (item.kind === "INTERVIEW_SUPPLEMENT" ? "采访附页" : "公开稿到刊提醒") +
        (item.speakerPartyId ? " · 发言者 " + (item.speakerName || item.speakerPartyId) + "（" + item.speakerPartyId + "）" : "") +
        " · 第 " + item.version + " 版 · " + item.deliveredAt + " 毫秒送达";
      article.append(title, details);
      if (item.bodyAvailable) {
        const body = document.createElement("p");
        body.textContent = item.body;
        article.append(body);
      } else if (acting) article.append(button("开始阅读", control => perform(control, "READ", item.docId, item.version)));
      $("newsletter-deliveries").append(article);
    }
  }

  return {clear, load};
})();
