# 旧 schema10 失败日志兼容夹具

2026-11-07由Root在B2b Runtime（PR29 f56dea4；Web PR30未改Runtime）实际执行原 `WorldRuntimeLiveDecisionTest.liveFailureUsesSafeJsonAndFullIdentity` 生成。原方法的引号/换行、全身份、重开及篡改拒绝断言全部通过；不是手写伪造日志。程序和运行证据位于 `logs/implementation-20261106-continued/live-completion-root/RootLegacyFailureFixture.java` 与 `legacy-fixture.log`。

B2c收紧live失败输入为固定码；保留这些原字节作为读旧日志回归，继续验证旧任意文本reason/quoted action的恢复和逐批身份核对。仅含测试人物、模拟账和provider:test，无真实Key/调用/真实资金。gzip仅压缩，测试解压后使用原WorldJournal恢复。

- `failure-1.journal.gz`: 原journal SHA-256 `f6103cb21ce11120f63ddf4886489231cc79b179226acf92ea4056d6d164de52`，压缩4701字节。
- `failure-2.journal.gz`: 原journal SHA-256 `e9dc0c4222e7cfaf9f58a3d195d9dd4654af2be492620227aec2923b9a555f59`，压缩4716字节。
