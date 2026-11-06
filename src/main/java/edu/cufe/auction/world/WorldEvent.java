package edu.cufe.auction.world;

import java.util.Objects;

/**
 * 不可变世界事件；具体领域模块负责校验类型与载荷的业务含义。
 * @param eventId 运行内唯一事件标识
 * @param type 版本化的事件类型
 * @param payload 不可变事件原文
 */
public record WorldEvent(String eventId, String type, String payload) {
    /** 校验事件的必要字段，不自动生成或修改原文。 */
    public WorldEvent {
        if (eventId == null || eventId.isBlank() || type == null || type.isBlank()) {
            throw new IllegalArgumentException("事件标识和类型不能为空");
        }
        Objects.requireNonNull(payload, "payload");
    }
}
