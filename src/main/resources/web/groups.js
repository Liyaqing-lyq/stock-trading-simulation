"use strict";

// 群组与平台页面：管理员读审计，人物只读本人有权限的群、邀请、帖子与公开目录。
// 依赖 app.js 的全局 $/request/cell/fillTable/isCurrent/state/sessionLost。

/** 群组面板在人物视角下必须放在 #person 内，否则随 #admin 一起隐藏。 */
function placeGroupsPanel() {
  const panel = document.querySelector('[data-panel="groups"]');
  if (!panel) return;
  const host = state.mode === "person" ? $("person") : $("admin");
  if (host && panel.parentElement !== host) host.appendChild(panel);
  panel.hidden = state.mode !== "person" && state.page !== "groups";
}

function clearGroupsDom() {
  for (const id of ["groups-mine", "groups-invites", "groups-posts", "groups-feed",
    "groups-audit-groups", "groups-audit-members", "groups-audit-invites",
    "groups-audit-posts", "groups-audit-versions", "groups-audit-platform",
    "groups-audit-frames", "groups-audit-reports"]) {
    if ($(id)) fillTable(id, [], () => undefined);
  }
  state.groups = null;
  state.personGroups = null;
  $("groups-result").textContent = "";
  $("groups-result").hidden = true;
  $("groups-note").textContent = "";
  for (const id of ["groups-title", "groups-body", "groups-cites", "groups-doc-id",
    "groups-invite-id", "groups-invitee", "groups-group-id", "groups-based-on"]) $(id).value = "";
  $("groups-accept").checked = false;
}

/** 群组页：管理员读审计，人物只读本人有权限的投影。 */
async function loadGroups(generation, pageVersion) {
  const mode = state.mode;
  const admin = mode === "admin";
  const version = pageVersion === null || pageVersion === undefined
    ? state.pageVersion : pageVersion;
  const url = admin ? "/api/admin/groups" : "/api/me/groups";
  let result;
  try {
    result = await request("GET", url);
  } catch (failure) {
    if (isCurrent(generation) && state.mode === mode && state.pageVersion === version
        && (!admin || state.page === "groups") && $("groups-note")) {
      $("groups-note").textContent = "读取失败，请稍后刷新重试。";
    }
    return;
  }
  if (!isCurrent(generation) || state.mode !== mode || state.pageVersion !== version
      || (admin && state.page !== "groups")) return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status !== 200 || !result.payload) {
    clearGroupsDom();
    if ($("groups-note")) $("groups-note").textContent = "读取失败（" + result.status + "）。";
    return;
  }
  if (admin) {
    state.groups = result.payload;
    renderGroupAudit(result.payload);
  } else {
    state.personGroups = result.payload;
    renderGroupPerson(result.payload);
  }
}

function renderGroupPerson(view) {
  $("groups-person").hidden = false;
  $("groups-admin").hidden = true;
  const groups = view.groups || [];
  fillTable("groups-mine", groups, (tr, row) => {
    cell(tr, row.groupId); cell(tr, row.joinPolicy); cell(tr, row.historyPolicy);
    cell(tr, row.joinedAt, "num"); cell(tr, row.owner ? "本人" : "—");
  });
  $("groups-mine-empty").hidden = groups.length > 0;
  const invitations = view.invitations || [];
  fillTable("groups-invites", invitations, (tr, row) => {
    cell(tr, row.inviteId); cell(tr, row.groupId); cell(tr, row.inviter);
    cell(tr, row.invitedAt, "num");
    const td = document.createElement("td");
    for (const accept of [true, false]) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = accept ? "接受" : "拒绝";
      button.addEventListener("click", () => submitGroupAction({
        action: "RESPOND", inviteId: row.inviteId, accept: accept
      }));
      td.appendChild(button);
    }
    tr.appendChild(td);
  });
  $("groups-invites-empty").hidden = invitations.length > 0;
  const posts = view.posts || [];
  fillTable("groups-posts", posts, (tr, row) => {
    cell(tr, row.docId); cell(tr, row.version, "num"); cell(tr, row.groupId);
    cell(tr, row.author); cell(tr, row.title);
    cell(tr, row.read ? row.body : "未读完（正文需真实读完且可回忆）");
    const td = document.createElement("td");
    const read = document.createElement("button");
    read.type = "button";
    read.textContent = "开始阅读";
    read.addEventListener("click", () => startGroupRead(row.docId, row.version));
    td.appendChild(read);
    tr.appendChild(td);
  });
  $("groups-posts-empty").hidden = posts.length > 0;
  fillTable("groups-feed", view.feed || [], (tr, row) => {
    cell(tr, row.docId); cell(tr, row.version, "num"); cell(tr, row.title);
    cell(tr, row.publisher); cell(tr, row.publishedAt, "num"); cell(tr, row.kind);
  });
  $("groups-note").textContent = view.enabled
    ? "群与平台动作按冻结正工时排程；完成与阅读以世界记录为准。"
    : "当前档案未启用群组与平台模块。";
}

function renderGroupAudit(view) {
  $("groups-person").hidden = true;
  $("groups-admin").hidden = false;
  fillTable("groups-audit-groups", view.groups || [], (tr, row) => {
    cell(tr, row.groupId); cell(tr, row.owner); cell(tr, row.joinPolicy);
    cell(tr, row.historyPolicy); cell(tr, (row.initialMembers || []).join("、"));
  });
  fillTable("groups-audit-members", view.memberships || [], (tr, row) => {
    cell(tr, row.groupId); cell(tr, row.person); cell(tr, row.joinedAt, "num");
    cell(tr, row.leftAt || "在群"); cell(tr, row.inviteId || "—");
  });
  fillTable("groups-audit-invites", view.invitations || [], (tr, row) => {
    cell(tr, row.inviteId); cell(tr, row.groupId); cell(tr, row.inviter);
    cell(tr, row.invitee); cell(tr, row.status); cell(tr, row.invitedAt, "num");
  });
  fillTable("groups-audit-posts", view.posts || [], (tr, row) => {
    cell(tr, row.postId); cell(tr, row.docId); cell(tr, row.groupId); cell(tr, row.author);
    cell(tr, row.status); cell(tr, row.publishedAt, "num");
    cell(tr, (row.declaredRefs || []).join("、")); cell(tr, (row.originRefs || []).join("、"));
  });
  fillTable("groups-audit-versions", view.versions || [], (tr, row) => {
    cell(tr, row.docId); cell(tr, row.version, "num"); cell(tr, row.basedOnVersion, "num");
    cell(tr, row.author); cell(tr, row.status); cell(tr, row.publishedAt, "num");
    cell(tr, (row.originRefs || []).join("、"));
  });
  fillTable("groups-audit-platform", view.platformPosts || [], (tr, row) => {
    cell(tr, row.docId); cell(tr, row.version, "num"); cell(tr, row.author);
    cell(tr, row.status); cell(tr, row.publishedAt, "num");
    cell(tr, (row.originRefs || []).join("、"));
  });
  fillTable("groups-audit-frames", view.frames || [], (tr, row) => {
    cell(tr, row.actionId); cell(tr, row.attemptId); cell(tr, row.inputDigest);
    cell(tr, row.observedAt, "num"); cell(tr, (row.entries || []).join("、"));
  });
  fillTable("groups-audit-reports", view.reports || [], (tr, row) => {
    cell(tr, row.taskId); cell(tr, row.person); cell(tr, row.action);
    cell(tr, row.accepted ? "已接受" : "已拒绝"); cell(tr, row.reason);
    cell(tr, row.subject); cell(tr, row.atMillis, "num");
  });
  $("groups-audit-note").textContent = view.enabled
    ? "上帝审计含成员段、邀请、帖子/版本、来源根、呈现帧与拒绝细分原因；人物视图不含这些字段。"
    : "当前档案未启用群组与平台模块。";
}

function groupActionDue(action) {
  const view = state.personGroups || {};
  const millis = (view.actionMillis || {})[action];
  const now = BigInt(view.timeMillis || "0");
  return Number(now + BigInt(millis || "0"));
}

/** 一次真实动作：due = 当前时点 + 冻结工时；回执只表示已排程。 */
async function submitGroupAction(extra) {
  const view = state.personGroups;
  if (!view || state.mode !== "person") return;
  const generation = state.generation;
  const action = extra.action || $("groups-action").value;
  const stamp = String(Date.now());
  const cites = [];
  for (const item of ($("groups-cites").value || "").split(",")) {
    const text = item.trim();
    if (!text) continue;
    const parts = text.split("#");
    cites.push({ docId: parts[0], version: Number(parts[1]) });
  }
  const payload = {
    expectedPosition: Number(view.position),
    commandId: "web-g-" + stamp,
    taskId: "web-g-task-" + stamp,
    dueTimeMillis: groupActionDue(action),
    action: action,
    groupId: extra.groupId || $("groups-group-id").value || "",
    inviteePartyId: extra.inviteePartyId || $("groups-invitee").value || "",
    inviteId: extra.inviteId || $("groups-invite-id").value || "",
    accept: extra.accept !== undefined ? extra.accept : $("groups-accept").checked,
    docId: extra.docId || $("groups-doc-id").value || "",
    basedOnVersion: Number(extra.basedOnVersion || $("groups-based-on").value || 0),
    title: extra.title || $("groups-title").value || "",
    body: extra.body || $("groups-body").value || "",
    cites: cites
  };
  let result;
  try {
    result = await request("POST", "/api/me/groups/action", payload);
  } catch (failure) {
    if (!isCurrent(generation) || state.mode !== "person") return;
    $("groups-result").hidden = false;
    $("groups-result").textContent = "提交失败：" + failure.message;
    return;
  }
  if (!isCurrent(generation) || state.mode !== "person") return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  if (result.status === 409) {
    $("groups-result").hidden = false;
    $("groups-result").textContent = "记录已变化，请刷新后再提交。";
    await loadGroups(state.generation, null);
    return;
  }
  $("groups-result").hidden = false;
  if (result.status !== 200 || !result.payload) {
    $("groups-result").textContent = "动作未被接受（" + result.status + "）。";
    return;
  }
  $("groups-result").textContent = "已排程：" + (result.payload.receipt || "—")
    + "（完成与阅读以世界记录为准）";
  await loadGroups(state.generation, null);
}

/** 复用唯一阅读入口：开始阅读的真实工时由资料冻结。 */
async function startGroupRead(docId, version) {
  const view = state.personGroups;
  if (!view || state.mode !== "person") return;
  const generation = state.generation;
  const stamp = String(Date.now());
  const result = await request("POST", "/api/me/read", {
    expectedPosition: Number(view.position),
    commandId: "web-read-g-" + stamp,
    taskId: "web-read-g-task-" + stamp,
    dueTimeMillis: Number(view.timeMillis || "0") + 1,
    docId: docId,
    version: Number(version)
  });
  if (!isCurrent(generation) || state.mode !== "person") return;
  if (result.status === 401 || result.status === 403) { sessionLost(); return; }
  $("groups-result").hidden = false;
  $("groups-result").textContent = result.status === 200
    ? "阅读已排程（读完后正文才可见）"
    : "阅读未被接受（" + result.status + "）。";
  await loadGroups(state.generation, null);
}

document.addEventListener("DOMContentLoaded", function () {
  const form = $("groups-form");
  if (form) {
    form.addEventListener("submit", function (event) {
      event.preventDefault();
      const generation = state.generation;
      submitGroupAction({}).catch(function (failure) {
        if (!isCurrent(generation) || state.mode !== "person") return;
        $("groups-result").hidden = false;
        $("groups-result").textContent = "提交失败：" + failure.message;
      });
    });
  }
});
