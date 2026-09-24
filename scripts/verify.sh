#!/usr/bin/env bash
# 项目验证入口：一条命令跑完「测试套件 + 黑盒复算 [ + 界面启动 ]」。
#
#   bash scripts/verify.sh              # 套件 + 黑盒复算
#   bash scripts/verify.sh --with-gui   # 额外真启一次 JavaFX 界面（会短暂弹窗约 15 秒）
#
# 在 git-bash 中运行；Windows 侧通过 cmd.exe 调 mvnw.cmd（Maven 输出为 GBK，已 iconv 处理）。
set -u
cd "$(dirname "$0")/.." || exit 2
PROJ="$(pwd)"
LOG="logs/verify"          # 注意：不能放 target/，第 1 步的 clean 会把它删掉
mkdir -p "$LOG"
FAIL=0
ok()  { echo "  [OK]   $*"; }
bad() { echo "  [FAIL] $*"; FAIL=1; }
gbk() { iconv -f GBK -t UTF-8 "$1" 2>/dev/null; }

echo "== 1/3 测试套件：mvnw.cmd clean test =="
cmd.exe /c "mvnw.cmd -B clean test" > "$LOG/suite.log" 2>&1
if grep -q "BUILD SUCCESS" "$LOG/suite.log" && gbk "$LOG/suite.log" | grep -qE "Tests run: [0-9]+, Failures: 0, Errors: 0"; then
  ok "$(gbk "$LOG/suite.log" | grep -E '^\[INFO\] Tests run: .*Skipped: 0$' | tail -1)"
else
  bad "套件未通过，详见 $LOG/suite.log"
fi

echo "== 2/3 黑盒复算：真跑一次模拟，从原始产物核对口径 =="
rm -rf data
cmd.exe /c "mvnw.cmd -B -q exec:java -Dexec.mainClass=edu.cufe.auction.app.ConsoleApp -Dexec.args=8" \
  > "$LOG/demo.log" 2>&1
if gbk "$LOG/demo.log" | grep -q "排行榜已写入"; then
  ok "模拟运行并正常收盘"
else
  bad "模拟未正常收盘，详见 $LOG/demo.log"
fi
if python - > "$LOG/csv.log" 2>&1 <<'PY'
import csv, sys, pathlib
d = pathlib.Path("data")
ok = True
def bad(m):
    global ok; ok = False; print(f"  [FAIL] {m}")
try:
    trades = list(csv.DictReader(open(d / "trades.csv", encoding="utf-8")))
    orders = list(csv.DictReader(open(d / "orders.csv", encoding="utf-8")))
    ranks  = list(csv.DictReader(open(d / "ranking.csv", encoding="utf-8")))
except FileNotFoundError as e:
    print(f"  [FAIL] 缺少产物 {e.filename}"); sys.exit(1)
if not trades:
    bad("trades.csv 无成交记录")
for t in trades:
    if abs(float(t["成交金额"]) - float(t["成交价"]) * int(t["数量"])) > 0.005:
        bad(f"成交金额 ≠ 价格×数量：{t}")
    if int(t["数量"]) <= 0:
        bad(f"非正成交量：{t}")
if len({t["成交ID"] for t in trades}) != len(trades):
    bad("成交 ID 重复")
for o in orders:
    q, f = int(o["数量"]), int(o["已成交"])
    if q <= 0 or not 0 <= f <= q:
        bad(f"订单数量口径异常：{o}")
    if o["状态"] == "REJECTED" and f:
        bad(f"被拒订单却有成交量：{o}")
prev = None
for i, r in enumerate(ranks, 1):
    init, eq, ret = float(r["初始权益"]), float(r["期末权益"]), float(r["收益率"])
    if int(r["名次"]) != i:
        bad(f"名次不连续：{r['名次']}")
    if init <= 0 or abs((eq - init) / init - ret) > 1e-5:
        bad(f"收益率与权益口径不一致：{r}")
    if prev is not None and ret > prev + 1e-9:
        bad(f"排行榜未按收益率降序：{r}")
    prev = ret
print(f"  [OK]   trades {len(trades)} 笔、orders {len(orders)} 条、ranking {len(ranks)} 行，口径一致")
sys.exit(0 if ok else 1)
PY
then
  cat "$LOG/csv.log"
else
  bad "黑盒复算未通过"; cat "$LOG/csv.log"
fi

echo "== 3/3 界面（可选）=="
if [ "${1:-}" = "--with-gui" ]; then
  cmd.exe /c "mvnw.cmd -B -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt" > /dev/null 2>&1
  timeout -k 5 20 java -cp "target/classes;$(cat target/cp.txt)" \
    edu.cufe.auction.gui.Launcher > "$LOG/gui.log" 2>&1
  if gbk "$LOG/gui.log" | grep -q "界面已启动"; then
    ok "界面启动成功（日志：$LOG/gui.log）"
  else
    bad "界面未启动，详见 $LOG/gui.log"
  fi
else
  echo "  [SKIP] 未指定 --with-gui"
fi

echo
[ "$FAIL" -eq 0 ] && echo "全部通过。（日志目录：$LOG）" || echo "存在失败项，见上。"
exit "$FAIL"
