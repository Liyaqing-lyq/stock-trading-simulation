package edu.cufe.auction.world;

/**
 * 群组读取资格回调：只回答“某人此刻能否读某群某版本”。
 *
 * <p>回调由受信桥接层注入，通常查询真实成员段历史；{@link InformationModel} 不持有群成员表，
 * 也不复制群状态。版本发布时间用于 JOIN_TIME 一类的历史门，{@code at} 是判定时刻：开读时是
 * 当前时刻，完成时是该阅读单固定的 {@code due}，不是调用 {@code completeRead} 的墙钟/处理时刻。</p>
 *
 * <p>{@link #NONE} 对一切群返回 false；旧签名使用它，因此旧档案即使出现群受众也只能得到
 * 与未知/无权一致的拒绝，不会意外获得群权力。</p>
 */
@FunctionalInterface
public interface GroupAccess {

    /** 恒定拒绝；旧接口兼容与“无群解析器”场景使用。 */
    GroupAccess NONE = (groupId, personPartyId, docVersionPublishedAt, at) -> false;

    /**
     * 判断读取资格。
     *
     * @param groupId               群标识
     * @param personPartyId         读取者
     * @param docVersionPublishedAt 所读版本的发布时间
     * @param at                    判定时刻；完成时为该阅读单的 due
     * @return 允许新读取返回 true
     */
    boolean canRead(String groupId, String personPartyId, long docVersionPublishedAt, long at);
}
