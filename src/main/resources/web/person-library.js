"use strict";

globalThis.PersonLibrary = (() => {
  let actor, query = "", token, after, next, revision = 0, previous = [];
  const set = (id, text) => { $(id).textContent = text; };

  function clear() {
    revision++; actor = null; token = null; after = null; next = null; query = ""; previous = [];
    $("person-library").hidden = true;
    $("library-query").value = "";
    $("library-rows").replaceChildren();
    set("library-feedback", "");
  }

  async function load(generation) {
    const requestNumber = ++revision;
    const params = new URLSearchParams({q: query});
    if (token) params.set("token", token);
    if (after) params.set("after", after);
    const current = () => isCurrent(generation) && requestNumber === revision && state.mode === "person";
    const section = $("person-library");
    try {
      const result = await request("GET", "/api/me/library?" + params);
      if (!current()) return;
      if (result.status === 401 || result.status === 403) { clear(); sessionLost(); return; }
      if (result.status !== 200) {
        $("library-rows").replaceChildren();
        $("library-prev").disabled = $("library-next").disabled = true;
        set("library-feedback", result.payload?.message || "资料目录暂时无法载入。");
        return;
      }
      const page = result.payload;
      section.hidden = !page.enabled;
      if (!page.enabled) return;
      token = page.token; next = page.next;
      const rows = $("library-rows");
      rows.replaceChildren();
      for (const item of page.rows) {
        const article = document.createElement("article");
        const title = document.createElement("h3"); title.textContent = item.title;
        const ref = document.createElement("p"); ref.textContent = item.docId + " · 第 " + item.version + " 版";
        article.append(title, ref);
        if (typeof item.body === "string") {
          const details = document.createElement("details"), summary = document.createElement("summary");
          summary.textContent = "查看已读正文";
          const body = document.createElement("p"); body.textContent = item.body;
          details.append(summary, body); article.append(details);
        }
        if (item.readingDueMillis != null) {
          const status = document.createElement("p"); status.textContent = "阅读中，预计完成于 " + item.readingDueMillis + " 毫秒";
          article.append(status);
        } else if (globalThis.PersonTakeover?.canAct()) {
          const read = document.createElement("button"); read.type = "button"; read.dataset.personWrite = "true";
          read.textContent = item.body == null ? "开始阅读" : "重新阅读";
          read.onclick = async () => {
            read.disabled = true;
            try { await scheduleDocumentReading(null,item.docId,item.version,item.title,"library-feedback"); }
            finally { if (current()) read.disabled = false; }
          };
          article.append(read);
        }
        rows.append(article);
      }
      $("library-prev").disabled = !previous.length;
      $("library-next").disabled = !next;
      set("library-feedback", page.rows.length ? "第 " + (previous.length + 1) + " 页 · " + page.rows.length + " 项" : "本人当前可见范围内没有匹配资料。");
    } catch (failure) {
      if (current()) set("library-feedback", failure.message);
    }
  }

  async function refresh(person, generation) {
    if (actor !== person) { clear(); actor = person; }
    const form = $("library-search");
    form.onsubmit = e => {
      e.preventDefault(); query = $("library-query").value.trim(); token = null; after = null; previous = [];
      load(generation);
    };
    $("library-prev").onclick = () => { if (previous.length) { after = previous.pop(); load(generation); } };
    $("library-next").onclick = () => { if (next) { previous.push(after); after = next; next = null; load(generation); } };
    $("library-refresh").onclick = () => load(generation);
    await load(generation);
  }
  return {clear,refresh};
})();
