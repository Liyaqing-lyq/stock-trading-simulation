# 原型季度实验

`PrototypeCycleRun` 使用正式 Java 世界入口执行 90 个连续实验日（3 × 30 日）。它是规则实验驱动，不是模型自主经营证明，不映射真实交易日历。参数为全新输出目录、日数（1–90）和可选的本机五Key文件；不传第三个参数就不调用提供方。已有日志的目录会被拒绝，不能覆盖失败候选。

真实董事会议、阅读、投票、任免和预算先完成，再执行经理经营与研究→经理目标→风控审核→交易员子单。所有资金变化和成交由 Java 处理。独立持有人只根据自身库存和公开信息报价，不读取基金目标；需求不足时允许零成交。药研45/180/360日、工程80/150日验收和陈酿周期均保留。

在仓库 PowerShell 中编译运行（先完成项目构建，不与同一 `target` 的其它 Maven 操作并行）：

```powershell
$env:JAVA_HOME='C:/Users/JXGU/DevTools/Runtimes/Java/current'
./mvnw.cmd -q compile dependency:build-classpath '-Dmdep.outputFile=target/prototype-cp.txt'
$cp='target/classes;'+(Get-Content target/prototype-cp.txt -Raw).Trim()
New-Item -ItemType Directory -Force target/prototype-driver | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp $cp -d target/prototype-driver scripts/prototype-run/PrototypeCycleRun.java scripts/prototype-run/PrototypeGovernance.java
& "$env:JAVA_HOME/bin/java.exe" -Xmx1536m '-Dfile.encoding=UTF-8' -cp "target/prototype-driver;$cp" PrototypeCycleRun logs/my-new-quarter 90
python scripts/prototype-run/audit_cycle.py logs/my-new-quarter
```

驱动保留 `policy.json`、开局、90个每日导出、分类动作记录、最终状态及关闭重开后的状态。恢复逐帧检验，耗时明显长于读取单个最终快照，请等待进程真实退出。Set专用序列化只消除集合顺序差异，事件、任务和其它List顺序不排序。

`audit_cycle.py` 不导入 Java 实现，使用十进制从每日导出复算现金、全部股份、交易资金腿、被动方价格、收盘冻结、十公司会计恒等式、三次关期和恢复一致性。只有全部断言通过才产生 `independent-audit.json` 和供上帝组织页展示的 `experiment-report.json`。该验收固定90日，不用于短探测或不同策略的数据。

本轮完整证据在 `logs/implementation-20261115-prototype-run/`（本机，未提交大日志）；正式候选 `quarter-3` 与运行源码 `quarter-runtime-3` 分别冻结。Web使用其独立副本，以便继续操作不会修改实验原始证据。旧档、失败候选和三次真实模型试调均保留并分开说明。

季度主档已解决全历史快照驻留问题。分支封存件扫描仍会物化父前缀；季度大档的分支内存与启动时长未验收，不能把较短档案的分支测试当作季度规模保证。
