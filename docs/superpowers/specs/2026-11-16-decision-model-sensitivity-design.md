# 决策模型敏感性只读对照

前三轮输入诊断仍存在已提供事实被误读、无据等待与计划缺失，不能继续把每次缺行动都归为资料不够。本轮检查模型选择这一因素，不再叠加新提示或新字段。

固定三个已有虚构角色切点：v5call304（mfgversa经理，采购被缺预算拒绝）、v5call396（mfgstruct主席，经理ACTIVE而无预算）、Task14call537 contextual（mfgtech主席，有预算无经理）。每切点输入逐字冻结；每个模型3重复，共18请求。对照使用现有客户端已支持的deepseek-flash与deepseek-v4-pro；model是唯一请求参数差异，system仍为PLANNING_SYSTEM_TEXT，maxOutputTokens2048，原slot与input不变，无provider随机种子控制，底层权重UNKNOWN。

两个模型均访问现有官方DeepSeek endpoint，不改变目的地或发送范围；仅已使用过的虚构角色投影，无world-cut/源码/凭据进正文。18HTTP/10分钟/单次60秒/不重试，任何提供方失败即停留证；费用UNKNOWN，不推定pro必优。

读回全部rawcalls/replies/provider来源，逐切点核对同一输入两臂SHA相同、只model不同；再校验原回复合同、工具参数和当前事实/权限/计划合理性。WAIT合法，动作意图不计执行成功；不能从一个较强回答外推世界已经成立。若模型有明确更好的合法闭环建议，才考虑冻结该模型做实际世界试跑；若无，则重新评估角色目标/沟通机制，不继续添加同类事实索引。
