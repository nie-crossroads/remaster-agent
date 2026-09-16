package com.remasteragent.eval.support;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 定位仓库根目录。
 *
 * <p>评测集的样本路径都写成「相对仓库根」的形式（{@code examples/eval/billing-legacy}），
 * 因为绝对路径会让 catalog 换台机器就失效 —— 而这个评测集的核心诉求就是可复现。
 *
 * <p>那么运行时就得把相对路径还原成绝对路径。做法是从起点向上找「含
 * {@code examples/eval/catalog.yaml} 的那一层」：不依赖 {@code user.dir}，
 * 也不依赖模块目录深度，因此无论从仓库根、从 {@code AgentServer/}、还是从
 * 模块目录下执行，结果都一样。
 */
public final class RepoRoot {

    /** catalog.yaml 相对仓库根的位置，也是「这里就是仓库根」的判据。 */
    public static final String CATALOG_RELATIVE = "examples/eval/catalog.yaml";

    private RepoRoot() {
    }

    /**
     * 从 {@code start} 向上查找仓库根。
     *
     * @throws IllegalStateException 一路找到文件系统根都没找到
     */
    public static Path locate(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(CATALOG_RELATIVE))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "从 " + start.toAbsolutePath() + " 向上没找到仓库根（判据: 存在 " + CATALOG_RELATIVE + "）");
    }

    /** 以当前工作目录为起点定位。 */
    public static Path locateFromWorkingDirectory() {
        return locate(Path.of(""));
    }

    /** catalog.yaml 的绝对路径。 */
    public static Path catalogFile(Path repoRoot) {
        return repoRoot.resolve(CATALOG_RELATIVE);
    }
}
