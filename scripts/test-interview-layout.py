"""核对采访页面真实标签嵌套，避免管理员数据落到人物视图之外。"""
from html.parser import HTMLParser
from pathlib import Path
import sys

VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link",
        "meta", "param", "source", "track", "wbr"}


class Layout(HTMLParser):
    def __init__(self):
        super().__init__()
        self.stack = []
        self.nodes = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        self.nodes.append((tag, attrs, tuple(self.stack)))
        if tag not in VOID:
            self.stack.append((tag, attrs))

    def handle_endtag(self, tag):
        if tag not in VOID:
            assert self.stack and self.stack[-1][0] == tag, f"mismatched closing tag: {tag}"
            self.stack.pop()

    def unique(self, attribute, value):
        found = [node for node in self.nodes if node[1].get(attribute) == value]
        assert len(found) == 1, f"expected one {attribute}={value}, got {len(found)}"
        return found[0]


def inside(node, attribute, value):
    return any(attrs.get(attribute) == value for _, attrs in node[2])


web = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "src/main/resources/web"
layout = Layout()
layout.feed((web / "index.html").read_text(encoding="utf-8"))
layout.close()
assert not layout.stack, "unclosed tags"
layout.unique("id", "admin")
layout.unique("id", "person")
panel = layout.unique("data-panel", "interview")
assert inside(panel, "id", "admin"), "interview audit must be inside admin"
footer = layout.unique("class", "foot")
assert not inside(footer, "id", "admin"), "footer must be outside admin"
assert layout.nodes.index(panel) < layout.nodes.index(footer), "audit must precede footer"
personal = layout.unique("id", "person-interview")
assert inside(personal, "id", "person") and not inside(personal, "id", "admin")
for table_id, columns in {"interview-invites": 9, "interview-sessions": 5,
                          "interview-works": 8, "interview-budgets": 4}.items():
    table = layout.unique("id", table_id)
    assert table[0] == "table" and inside(table, "data-panel", "interview"), table_id
    descendants = [node for node in layout.nodes if inside(node, "id", table_id)]
    assert sum(node[0] == "th" for node in descendants) == columns, table_id
    assert sum(node[0] == "tbody" for node in descendants) == 1, table_id
for table_id in ("person-interview-invites", "person-interview-contacts"):
    assert inside(layout.unique("id", table_id), "id", "person-interview"), table_id

# 逐轮访谈：管理员四张表必须在 admin 的采访面板内；个人两张表必须在 person-interview 内
for table_id, columns in {"dialogue-sessions": 6, "dialogue-turns": 12,
                          "dialogue-closes": 2, "dialogue-reports": 7}.items():
    table = layout.unique("id", table_id)
    assert table[0] == "table" and inside(table, "data-panel", "interview"), table_id
    descendants = [node for node in layout.nodes if inside(node, "id", table_id)]
    assert sum(node[0] == "th" for node in descendants) == columns, table_id
    assert sum(node[0] == "tbody" for node in descendants) == 1, table_id
for table_id, columns in {"person-dialogue-sessions": 6, "person-dialogue-turns": 9}.items():
    table = layout.unique("id", table_id)
    assert table[0] == "table" and inside(table, "id", "person-interview"), table_id
    descendants = [node for node in layout.nodes if inside(node, "id", table_id)]
    assert sum(node[0] == "th" for node in descendants) == columns, table_id

# 证据与共享（schema16）：管理三表在 admin 的证据面板内；人物两表在 person 证据卡内
for table_id, columns in {"evidence-roots": 5, "evidence-aliases": 7,
                          "evidence-shares": 8}.items():
    table = layout.unique("id", table_id)
    assert table[0] == "table" and inside(table, "data-panel", "evidence"), table_id
    descendants = [node for node in layout.nodes if inside(node, "id", table_id)]
    assert sum(node[0] == "th" for node in descendants) == columns, table_id
    assert sum(node[0] == "tbody" for node in descendants) == 1, table_id
for table_id, columns in {"person-evidence-labels": 4, "person-evidence-shares": 4}.items():
    table = layout.unique("id", table_id)
    assert table[0] == "table" and inside(table, "id", "person-evidence"), table_id
    descendants = [node for node in layout.nodes if inside(node, "id", table_id)]
    assert sum(node[0] == "th" for node in descendants) == columns, table_id

# 消息（schema17）：管理四表在 admin 的消息面板内；人物三表在 person-social 内
for table_id, columns in {"social-messages": 10, "social-blocks": 4,
                          "social-reports": 7, "social-contacts": 5}.items():
    table = layout.unique("id", table_id)
    assert table[0] == "table" and inside(table, "data-panel", "social"), table_id
    descendants = [node for node in layout.nodes if inside(node, "id", table_id)]
    assert sum(node[0] == "th" for node in descendants) == columns, table_id
    assert sum(node[0] == "tbody" for node in descendants) == 1, table_id
for table_id, columns in {"person-social-inbox": 6, "person-social-outbox": 5,
                          "person-social-contacts": 3}.items():
    table = layout.unique("id", table_id)
    assert table[0] == "table" and inside(table, "id", "person-social"), table_id
    descendants = [node for node in layout.nodes if inside(node, "id", table_id)]
    assert sum(node[0] == "th" for node in descendants) == columns, table_id
print("INTERVIEW_LAYOUT=PASS")
