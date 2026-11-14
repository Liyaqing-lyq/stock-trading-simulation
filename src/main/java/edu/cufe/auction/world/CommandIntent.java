package edu.cufe.auction.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 通用排程原意信封（{@code command-intent} 模块，版本 {@value #VERSION}）。
 *
 * <p>旧规则档的外部排程命令正文只写业务参数：治理、任职、分红、发布、阅读与工程动作的原命令里
 * 没有 {@code taskId}、绝对到期或工时，恢复时只能把「待办参数 == 命令正文」当作来源证明。
 * 于是「同步改待办与排程事件」这种自洽篡改无法被鉴别——本信封把一次外部排程的完整意图
 * （版本、原命令标识、任务标识、动作种类、阶段、绝对到期、工时与原始域请求）写进
 * {@code commandContents}，待办只由这份意图派生。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code intent}：信封版本，当前只接受 {@code 1}；未知版本显式拒绝。</li>
 *   <li>{@code commandId}：原外部命令标识（与提交的 {@code cmd:<commandId>} 一致）。</li>
 *   <li>{@code taskId}：本次排程的任务标识，运行内不复用。</li>
 *   <li>{@code kind}：真实完成帧的任务种类；工程动作这里记录 BEGIN 的目标种类。</li>
 *   <li>{@code phase}：真实完成帧的固定阶段名。</li>
 *   <li>{@code dueTimeMillis}：真实完成时点的绝对模拟毫秒（不是 BEGIN 屏障时点）。</li>
 *   <li>{@code durationMillis}：冻结工时；不占工时的人物/系统入口为 0。</li>
 *   <li>{@code body}：原始域请求正文（规范化 JSON 原文），现有业务解码继续读它。</li>
 * </ul>
 *
 * <p>本类型只承载原意，不保存第二份可改写的世界状态：现金、订单、任职与制造仍以各自权威子集为准。
 * 旧规则档的命令正文与已发布日志字节不变，旧档缺的信息继续明示为限制，不做迁移。</p>
 *
 * @param intent         信封版本
 * @param commandId      原外部命令标识
 * @param taskId         任务标识
 * @param kind           真实完成帧的任务种类
 * @param phase          真实完成帧的固定阶段名
 * @param dueTimeMillis  真实完成时点的绝对模拟毫秒
 * @param durationMillis 冻结工时；0 表示不占人物焦点
 * @param body           原始域请求正文
 */
record CommandIntent(int intent, String commandId, String taskId, String kind, String phase,
                     long dueTimeMillis, long durationMillis, String body) {

    /** 当前唯一信封版本。 */
    static final int VERSION = 1;

    /** 校验必需字段；缺一项就不是可重演的完整意图。 */
    CommandIntent {
        if (intent != VERSION) {
            throw new IllegalArgumentException("排程意图信封版本不支持：" + intent);
        }
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("排程意图信封缺少原命令标识");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("排程意图信封缺少任务标识");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("排程意图信封缺少任务种类");
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("排程意图信封缺少阶段");
        }
        if (dueTimeMillis <= 0) {
            throw new IllegalArgumentException("排程意图信封的完成时点必须为正：" + dueTimeMillis);
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("排程意图信封的工时不能为负：" + durationMillis);
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("排程意图信封缺少原始域请求");
        }
    }

    /**
     * 由真实排程入口已算出的意图构造信封；不读取待办或其它可变状态。
     *
     * @param commandId      原外部命令标识
     * @param taskId         任务标识
     * @param kind           真实完成帧的任务种类
     * @param phase          真实完成帧的固定阶段
     * @param dueTimeMillis  真实完成时点的绝对模拟毫秒
     * @param durationMillis 冻结工时
     * @param body           原始域请求正文
     * @return 可直接序列化进 {@code commandContents} 的信封
     */
    static CommandIntent of(String commandId, String taskId, String kind,
                            WorldScheduler.Phase phase, long dueTimeMillis, long durationMillis,
                            String body) {
        if (phase == null) {
            throw new IllegalArgumentException("排程意图信封缺少阶段");
        }
        return new CommandIntent(VERSION, commandId, taskId, kind, phase.name(), dueTimeMillis,
                durationMillis, body);
    }

    /** @return 阶段枚举；名称非法时拒绝，不静默退化成默认阶段 */
    WorldScheduler.Phase phaseOf() {
        try {
            return WorldScheduler.Phase.valueOf(phase);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("排程意图信封阶段未知：" + phase, invalid);
        }
    }

    /**
     * 识别并解析信封。
     *
     * <p>判别只用 {@code intent} 字段：没有该字段的正文是旧档/完整命令的域正文，返回 null；
     * 出现了 {@code intent} 却不合法（版本不支持、字段缺失、类型不符）一律抛出，不能把坏信封
     * 当成普通正文继续执行。</p>
     *
     * @param mapper  与运行一致的 JSON 映射器
     * @param content 已记录或待记录的命令正文
     * @return 信封；不是信封时返回 null
     * @throws IOException 看起来是信封但无法按当前版本解析
     */
    static CommandIntent decode(ObjectMapper mapper, String content) throws IOException {
        if (content == null) {
            return null;
        }
        JsonNode node;
        try {
            node = mapper.readTree(content);
        } catch (IOException | RuntimeException notJson) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode version = node.get("intent");
        if (version == null || version.isNull()) {
            return null;
        }
        if (!version.isIntegralNumber() || version.asInt() != VERSION) {
            throw new IOException("排程意图信封版本不支持：" + version.asText());
        }
        try {
            return mapper.treeToValue(node, CommandIntent.class);
        } catch (IOException | RuntimeException invalid) {
            throw new IOException("排程意图信封无法解析：" + invalid.getMessage(), invalid);
        }
    }
}
