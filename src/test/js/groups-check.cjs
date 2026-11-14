"use strict";

// 真实执行 groups.js 的按钮 handler 与字段绑定（不联网）：用最小 DOM 桩加载真实文件。
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const root = path.join(__dirname, "..", "..", "main", "resources", "web");

function element(id) {
  return {
    id: id, hidden: false, textContent: "", value: "", checked: false, children: [],
    listeners: {},
    appendChild(child) { this.children.push(child); return child; },
    addEventListener(type, handler) { (this.listeners[type] = this.listeners[type] || []).push(handler); },
    replaceChildren() { this.children = []; }
  };
}

const elements = new Map();
for (const id of ["groups-mine", "groups-invites", "groups-posts", "groups-feed",
  "groups-audit-groups", "groups-audit-members", "groups-audit-invites",
  "groups-audit-posts", "groups-audit-versions", "groups-audit-platform",
  "groups-audit-frames", "groups-audit-reports", "groups-mine-empty",
  "groups-invites-empty", "groups-posts-empty", "groups-note", "groups-result",
  "groups-action", "groups-group-id", "groups-invitee", "groups-invite-id",
  "groups-accept", "groups-doc-id", "groups-based-on", "groups-title", "groups-body",
  "groups-cites", "groups-form", "groups-person", "groups-admin", "person", "admin"]) {
  elements.set(id, element(id));
}

const state = { mode: "person", generation: 1, page: "groups", pageVersion: 7,
  personGroups: null, groups: null };
const calls = [];
let lastLoad = null;

const sandbox = {
  state: state,
  console: console,
  BigInt: BigInt,
  Number: Number,
  String: String,
  Date: Date,
  document: {
    createElement: () => element("created"),
    querySelector: () => elements.get("groups-person"),
    addEventListener: () => undefined
  },
  $: id => elements.get(id) || element(id),
  isCurrent: generation => generation === state.generation,
  sessionLost: () => { calls.push(["sessionLost"]); },
  cell: (tr, value, cls) => tr.children.push({ text: value === undefined ? "" : String(value), cls }),
  fillTable: (id, rows, builder) => {
    const table = elements.get(id);
    table.rows = rows;
    table.children = [];
    for (const row of rows) {
      const tr = element("tr");
      builder(tr, row);
      table.children.push(tr);
    }
  },
  request: async (method, url, body) => {
    calls.push([method, url, body]);
    if (url === "/api/me/groups") {
      if (lastLoad) return lastLoad;
      return { status: 200, payload: pageView() };
    }
    if (url === "/api/me/read") return { status: 200, payload: {} };
    if (url === "/api/me/groups/action") return { status: 200, payload: { receipt: "SCHEDULED:x" } };
    return { status: 200, payload: {} };
  }
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;

function pageView() {
  return {
    runId: "web-groups", position: "9", timeMillis: "1000", personId: "bob", enabled: true,
    groups: [{ groupId: "group:invite-only", joinPolicy: "INVITE_ONLY",
      historyPolicy: "JOIN_TIME", joinedAt: "0", owner: false }],
    invitations: [{ inviteId: "GINV-1:dave:1:cmd", groupId: "group:invite-only",
      inviter: "bob", invitedAt: "900" }],
    posts: [{ docId: "GDOC-GPOST-1", version: "1", groupId: "group:invite-only",
      author: "dave", title: "线索", body: "群内正文", publishedAt: "950", mine: false,
      read: true, refs: ["GDOC-PRIVATE-1#1"] }],
    feed: [{ docId: "PDOC-GPOST-2", version: "1", title: "公开转述", publisher: "bob",
      publishedAt: "1200", kind: "PLATFORM_POST" }],
    actionMillis: { JOIN: "10", INVITE: "10", RESPOND: "10", LEAVE: "10", POST: "20",
      CORRECTION: "20", PLATFORM_POST: "15", PLATFORM_CORRECTION: "15" }
  };
}

const context = vm.createContext(sandbox);
const code = fs.readFileSync(path.join(root, "groups.js"), "utf8");
vm.runInContext(code, context, { filename: "groups.js" });

function assert(flag, message) {
  if (!flag) throw new Error("ASSERT: " + message);
}

(async () => {
  // 1) 人物页渲染：邀请按钮带真实 inviteId；点"接受"触发真实动作请求
  state.personGroups = pageView();
  context.renderGroupPerson(state.personGroups);
  const inviteRow = elements.get("groups-invites").children[0];
  const acceptButton = inviteRow.children[4].children[0];
  assert(acceptButton.textContent === "接受", "邀请行必须有接受按钮");
  acceptButton.listeners.click[0]();
  await new Promise(resolve => setTimeout(resolve, 10));
  const acceptCall = calls.filter(c => c[0] === "POST" && c[1] === "/api/me/groups/action").pop();
  assert(acceptCall, "接受按钮必须发起真实动作请求");
  assert(acceptCall[2].action === "RESPOND", "按钮必须绑定 RESPOND");
  assert(acceptCall[2].inviteId === "GINV-1:dave:1:cmd", "必须带真实邀请标识");
  assert(acceptCall[2].accept === true, "接受按钮必须带 accept=true");
  assert(!("personPartyId" in acceptCall[2]), "请求不得自填人物");

  // 2) 动作表单：due = 当前时点 + 冻结工时；cites 解析为真实版本
  elements.get("groups-action").value = "POST";
  elements.get("groups-group-id").value = "group:invite-only";
  elements.get("groups-title").value = "标题";
  elements.get("groups-body").value = "正文";
  elements.get("groups-cites").value = "GDOC-1#1, GDOC-2#3";
  await context.submitGroupAction({});
  const postCall = calls.filter(c => c[0] === "POST" && c[1] === "/api/me/groups/action").pop();
  assert(postCall[2].action === "POST", "表单必须绑定所选动作");
  assert(postCall[2].dueTimeMillis === 1020, "due 必须等于时点+冻结工时：" + postCall[2].dueTimeMillis);
  assert(postCall[2].cites.length === 2 && postCall[2].cites[0].version === 1, "引用必须解析版本");

  // 3) 阅读按钮复用唯一入口 /api/me/read
  const readButton = elements.get("groups-posts").children[0].children[6].children[0];
  readButton.listeners.click[0]();
  await new Promise(resolve => setTimeout(resolve, 10));
  const readCall = calls.filter(c => c[0] === "POST" && c[1] === "/api/me/read").pop();
  assert(readCall && readCall[2].docId === "GDOC-GPOST-1", "阅读按钮必须复用 /api/me/read");

  // 4) 旧页面迟到响应不得灌回 DOM
  const beforeRows = JSON.stringify(elements.get("groups-mine").rows || []);
  let release;
  lastLoad = new Promise(resolve => { release = resolve; });
  const pending = context.loadGroups(1, 7);
  state.pageVersion = 8;
  release({ status: 200, payload: Object.assign(pageView(), { personId: "stale" }) });
  await pending;
  assert(JSON.stringify(elements.get("groups-mine").rows || []) === beforeRows,
    "旧 pageVersion 的迟到响应必须被丢弃");

  // 5) 身份切换清空状态与 DOM
  context.clearGroupsDom();
  assert(state.personGroups === null && state.groups === null, "身份切换必须清空群组状态");
  assert((elements.get("groups-posts").rows || []).length === 0, "身份切换必须清空帖子表");

  console.log("GROUPS-NODE-OK");
})().catch(failure => {
  console.error(failure);
  process.exit(1);
});
