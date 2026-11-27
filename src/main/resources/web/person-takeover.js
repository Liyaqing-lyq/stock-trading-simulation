"use strict";

globalThis.PersonTakeover = (() => {
  let mode = null;
  let actor = null;
  let token = null;
  let launcher = false;
  let available = false;

  function update() {
    const person = state.mode === "person";
    const admin = state.mode === "admin";
    const observing = mode === "READ_ONLY", acting = mode === "TAKEOVER";
    document.documentElement.classList.toggle("person-observing", person && observing);
    document.documentElement.classList.toggle("admin-can-takeover", admin && observing && launcher);
    const mark = $("context-intervention");
    mark.hidden = !((admin || person) && (acting || (person && observing)));
    mark.textContent = acting ? "人工干预 · GOD_TAKEOVER" : person && observing ? "本人视角 · 只读观察" : "";
    const selected = $("takeover-selected");
    $("person-form").querySelector('[type="submit"]').textContent = admin && observing ? "只读查看此人" : "切换人物身份";
    selected.hidden = !admin || !observing;
    selected.disabled = !launcher || state.position == null;
    selected.title = launcher ? "" : "当前服务未启用分支启动";
    const mine = $("takeover-actor");
    mine.hidden = !person || !observing || !available;
    mine.disabled = !token;
    const note = $("person-observe-note");
    note.hidden = !person || !observing;
    note.textContent = available ? "正在查看父档。要以本人权限阅读资料、交易或发出预约，请先创建干预子档。"
      : "此档提供本人只读视角。";
  }

  function reset() {
    mode = null; actor = null; token = null; launcher = false; available = false;
    $("takeover-feedback").textContent = "";
    update();
  }

  async function admin() {
    const generation = state.generation;
    const result = await request("GET", "/api/admin/runs");
    if (!isCurrent(generation) || state.mode !== "admin" || result.status !== 200) return;
    mode = result.payload.personActionMode;
    launcher = result.payload.launcher === true;
    update();
  }

  function person(value, id, canEnter) {
    mode = value; actor = id; token = null; available = canEnter === "true";
    update();
  }

  function cut(observationToken) {
    token = typeof observationToken === "string" && observationToken.length ? observationToken : null;
    update();
  }

  async function start(id, approvedPosition) {
    const admin = state.mode === "admin", generation = state.generation;
    const feedback = $("takeover-feedback");
    if (mode !== "READ_ONLY" || (admin && !launcher) || (!admin && (!available || state.mode !== "person" || id !== actor))
        || approvedPosition == null || !id) return;
    const body = {expectedPosition: String(approvedPosition)};
    if (admin) body.personId = id;
    feedback.textContent = "正在打开人工干预子档…";
    const result = await request("POST", admin ? "/api/admin/person-takeover" : "/api/me/takeover", body);
    if (!isCurrent(generation)) return;
    if (result.status !== 200) {
      feedback.textContent = result.payload?.error === "POSITION_MISMATCH" ? "确认位置已变化，请刷新后重新选择。"
        : result.payload?.message || "未能打开接管子档。";
      return;
    }
    const link = new URL(result.payload.location, location.href);
    const loopback = host => ["127.0.0.1", "localhost", "[::1]"].includes(host);
    if ((link.hostname !== location.hostname && !(loopback(link.hostname) && loopback(location.hostname))) ||
        !["http:", "https:"].includes(link.protocol) || link.username || link.password)
      throw new Error("子档未返回本机入口");
    if (admin) link.hash = "takeover-person=" + encodeURIComponent(id);
    location.assign(link.href);
  }

  async function enterMarkedChild() {
    if (!location.hash.startsWith("#takeover-person=")) return false;
    const id = decodeURIComponent(location.hash.slice("#takeover-person=".length));
    const meta = await request("GET", "/api/admin/runs");
    if (!id || meta.status !== 200 || meta.payload?.personActionMode !== "TAKEOVER") return false;
    history.replaceState(history.state, "", location.pathname + location.search);
    await switchPerson(id);
    return true;
  }

  function blocks(method, url) {
    return state.mode === "person" && mode === "READ_ONLY" && method === "POST" && url.startsWith("/api/me/")
      && url !== "/api/me/view" && url !== "/api/me/takeover";
  }

  document.addEventListener("DOMContentLoaded", () => {
    $("takeover-selected").addEventListener("click", () => {
      const input = $("person-id");
      if (!input.reportValidity()) return;
      guard(start(input.value.trim(), state.position));
    });
    $("takeover-actor").addEventListener("click", () => guard(start(actor, token)));
  });
  return {reset, admin, person, cut, blocks, start, enterMarkedChild};
})();
