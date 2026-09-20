package com.remasteragent.common.agent;

import java.util.List;

/**
 * PARENT_UPGRADE 节点的产出。
 *
 * <p><b>为什么必须带上完整的新内容</b>：与 {@link DependencyUpgradeResult} 同一条理由 ——
 * 沙箱在任务挂起/重入队时会被重建（{@code WorkspacePreparer.prepare} 清空重建），
 * 而 pom 不在任何 REWRITE 节点的产物里。只记路径的话，重建后 parent 版本会退回升级前，
 * 后续 VERIFY 在旧 BOM 下编译新语法而必然失败，且失败原因指向「代码有问题」，与真因完全错位。
 * 带上内容，由 {@code DagScheduler.restoreWorkspace} 从 checkpoint 重放，这类静默错位就不可能发生。
 *
 * @param scannedPoms 扫过的 pom 数（含无需改动的）。<b>刻意记下来</b>：否则「一个都没改」与
 *                    「压根没找到 pom」在结果里长得一样，事后无法区分是「已达标」还是「找错了目录」
 * @param files       逐文件的改动（路径 + 新内容 + 改了什么），供 checkpoint 重放
 */
public record ParentUpgradeResult(
        int scannedPoms,
        List<FileChange> files
) {

    /**
     * 单个 pom 的改动。
     *
     * @param filePath   相对工程根的 pom 路径（根的 pom 就是 {@code pom.xml}）
     * @param newContent 改写后的完整文件内容
     * @param summary    一句话说明升级了什么（如 {@code spring-boot-starter-parent 版本: 2.7.17 → 3.5.16}）
     */
    public record FileChange(
            String filePath,
            String newContent,
            String summary
    ) {
    }

    /** 没有任何 pom 需要升级 parent 时的产出。 */
    public static ParentUpgradeResult noChange(int scannedPoms) {
        return new ParentUpgradeResult(scannedPoms, List.of());
    }

    /** 是否真的改动了文件。 */
    public boolean changed() {
        return !files.isEmpty();
    }
}
