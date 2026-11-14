package edu.cufe.auction.world;

/**
 * 董事会读取资格回调：只回答“某人此刻是否是某公司的真实章程席位”。
 *
 * <p>回调由受信桥接层注入，通常查询当前公司 charter 的 {@code Seat(seatId, partyId)} 与席位有效性；
 * {@link InformationModel} 不持有董事会状态，也不复制席位表。{@code at} 是判定时刻：查询目录与
 * 开始阅读时是当前时刻，完成阅读时是该阅读单固定的 {@code due} —— 与群组回调的时间口径一致。</p>
 *
 * <p>{@link #NONE} 对一切公司返回 false；旧签名使用它，因此旧档案即使出现董事会受众也只能得到
 * 与未知/无权一致的拒绝，不会意外获得董事权力。真实的 charter/人物绑定必须由未来 World 桥重新
 * 绑定并单独验收，本回调本身不证明身份。</p>
 */
@FunctionalInterface
public interface BoardAccess {

    /** 恒定拒绝；旧接口兼容与“无董事会解析器”场景使用。 */
    BoardAccess NONE = (companyPartyId, personPartyId, at) -> false;

    /**
     * 判断读取资格。
     *
     * @param companyPartyId 公司组织 partyId
     * @param personPartyId  读取者
     * @param at             判定时刻；完成阅读时为该阅读单的 {@code due}
     * @return 允许新读取返回 true
     */
    boolean canRead(String companyPartyId, String personPartyId, long at);
}
