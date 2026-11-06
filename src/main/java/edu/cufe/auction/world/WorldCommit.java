package edu.cufe.auction.world;

import java.util.List;
import java.util.Objects;

/**
 * 同时持久化事件与命令回报的不可变提交批次。
 * @param schemaVersion 存储格式版本，当前为 1
 * @param position 从 1 开始的连续提交位置
 * @param runId 世界运行标识
 * @param commandId 运行内幂等命令标识
 * @param commandContent 规范化命令原文，由调用方生成
 * @param result 命令的实际回报原文
 * @param events 此命令形成的事件，按因果顺序保存
 */
public record WorldCommit(int schemaVersion, long position, String runId, String commandId,
                          String commandContent, String result, List<WorldEvent> events) {
    /** 校验批次并防御性复制事件列表，避免提交后被修改。 */
    public WorldCommit {
        if (schemaVersion != 1 || position <= 0 || runId == null || runId.isBlank()
                || commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("提交版本、位置、运行或命令标识非法");
        }
        Objects.requireNonNull(commandContent, "commandContent");
        Objects.requireNonNull(result, "result");
        events = List.copyOf(events);
    }
}
