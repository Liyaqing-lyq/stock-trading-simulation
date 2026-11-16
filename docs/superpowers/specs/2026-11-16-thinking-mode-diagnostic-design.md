# 思考模式只读对照设计

新证据：DeepSeekClient.java:343硬编码thinking.type=disabled，之前所有真实试跑与Task21 Flash/Pro比较均为非思考模式。Task21两模型各9次全部WAIT，不能外推开启思考后的模型能力。

官方合同已于2026-11-16查询： https://api-docs.deepseek.com/api/create-chat-completion/ 与 https://api-docs.deepseek.com/guides/thinking_mode/ 。thinking.type支持enabled/disabled并优先于effort；effort high在开启时适用。max_tokens限制生成量；必须记录实际finish/outcome与用量，不假定最终JSON必有空间。

先做logs-only一次受控实验，生产代码不改：沿Task21原样的304/396/537三切点，deepseek-flash，每切点3重复×开/关两臂=18HTTP。每对system、user、model、slot、max_tokens=8192、reasoning_effort=high、JSON格式、stream=false、超时完全相同，仅thinking.type不同。与旧2048输出限额的试跑不直接归因比较，8192是两臂共同的新诊断预算。10分钟总墙钟，单次最多60秒，不重试，首次提供方失败/不完整/证据异常立即停止并留证，不修改中途预算。

在logs目录复制已冻结客户端为独立诊断类，只增加受控thinking开关和固定high effort，不修改源文件/target/frozen runtime。使用既有KeySlots服务器读取与固定官方endpoint，客户端原传输/响应上限/脱敏及最终content解析尽量保持；不保存或输出reasoning_content正文。保存完整最终回复、原始role输入、准确请求body无认证、mode/effort/token上限、提供方声明与用量。诊断不打开世界，不执行建议，不把思考内容当世界事实或做私有心理推断。

预检必须真正生成两种HTTP请求body，结构相等且只有thinking.type不同；模式选择、18schedule、输入SHA及源码/编译类哈希全部冻结。worldCommandsExecuted=0；保留失败。比较原回复合同、当前事实、具体工具参数和自主行动/等待依据，不以动作多就判成功；即使建议改善也须新版本冻结调用模式、正确用量账本、独审及真实世界验证后再宣称有效。
