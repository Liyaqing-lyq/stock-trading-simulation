package edu.cufe.auction.world;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 把父运行标识解析成<b>封存父前缀</b>路径的唯一入口。
 *
 * <p>B1 只提供接口：实现必须是可信本机运行仓储（见 {@link BranchRepository}），由运行索引给出
 * 已登记的封存件路径；不接受用户任意路径、不接受自报画像。解析不到就是
 * {@code BRANCH_SEAL_MISSING}，不做"仅查看"式降级，也不回退去打开活跃父档。</p>
 */
@FunctionalInterface
public interface ParentRunResolver {

    /**
     * @param parentRunId 父运行标识（来自分支首帧来源，已受白名单约束）
     * @return 封存父前缀路径；无法解析时返回 null
     * @throws IOException 解析过程本身失败
     */
    Path resolve(String parentRunId) throws IOException;
}
