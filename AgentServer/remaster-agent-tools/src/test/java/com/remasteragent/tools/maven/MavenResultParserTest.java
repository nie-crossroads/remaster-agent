package com.remasteragent.tools.maven;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavenResultParser#extractCompileErrors} 的确定性单测 —— 不依赖 LLM / DB / Maven。
 *
 * <p>这条路径值得被钉死，因为它是「回退重写能不能改对」的唯一输入：摘录里少了 javac 的
 * 明细续行，模型就只知道「第 129 行有问题」而不知道「缺的是哪个方法」，只能瞎猜 ——
 * 实测会出现重试三轮毫无进展、甚至原样返回（diff 为 0）的情况。
 *
 * <p>用内联的 Maven 控制台片段而不是真跑一次构建：这里要钉的是「文本 → 摘录」的解析契约，
 * 内联样本让断言完全自洽，也才能覆盖「两个编译阶段重复报错」这类真实构建里看运气才碰得到的情形。
 */
class MavenResultParserTest {

    /** 中文 Windows（GBK 控制台代码页）下 javac 的真实输出形态。 */
    private static final String CONSOLE_COMPILE_FAILED = """
            [INFO] --- compiler:3.14.1:compile (default-compile) @ legacy-demo ---
            [ERROR] /work/task-3/src/test/java/com/example/legacy/LegacySalesReportTest.java:[129,26] 找不到符号
              符号:   方法 formatDate(java.util.Date)
              位置:   类 com.example.legacy.LegacySalesReportTest
            [ERROR] /work/task-3/src/test/java/com/example/legacy/LegacySalesReportTest.java:[130,27] 找不到符号
              符号:   方法 formatCurrency(double)
              位置:   类 com.example.legacy.LegacySalesReportTest
            [INFO] BUILD FAILURE
            """;

    @Test
    void keepsJavacDetailLinesSoTheFeedbackIsActionable() {
        List<String> blocks = MavenResultParser.extractCompileErrors(
                CONSOLE_COMPILE_FAILED, Path.of("/work/task-3"), 40);

        assertEquals(2, blocks.size(), "应识别出两个错误块");

        String first = blocks.get(0);
        assertTrue(first.contains("LegacySalesReportTest.java:[129,26] 找不到符号"),
                "首行要保留定位与一句话诊断，实际: " + first);
        assertTrue(first.contains("符号:") && first.contains("formatDate(java.util.Date)"),
                "**必须**带上 javac 的符号明细行，否则模型不知道缺的是什么，实际: " + first);
        assertTrue(first.contains("位置:") && first.contains("LegacySalesReportTest"),
                "位置行同样要保留，实际: " + first);
    }

    @Test
    void dedupesBlocksReportedByBothCompileAndTestCompilePhases() {
        // Maven 会连续跑 default-compile 与 default-testCompile，同样的错误各报一遍
        String duplicated = CONSOLE_COMPILE_FAILED + CONSOLE_COMPILE_FAILED;

        List<String> blocks = MavenResultParser.extractCompileErrors(
                duplicated, Path.of("/work/task-3"), 40);

        assertEquals(2, blocks.size(), "两个阶段各报一遍同样的错误，去重后仍应只有两个块，实际: " + blocks);
    }

    @Test
    void relativizesSandboxAbsolutePaths() {
        List<String> blocks = MavenResultParser.extractCompileErrors(
                CONSOLE_COMPILE_FAILED, Path.of("/work/task-3"), 40);

        for (String block : blocks) {
            assertFalse(block.contains("/work/task-3/"),
                    "沙箱绝对路径应被换成工程内相对路径（临时目录名对模型是纯噪声），实际: " + block);
            assertTrue(block.startsWith("src/test/java/"),
                    "相对化后应以工程内路径开头，实际: " + block);
        }
    }

    @Test
    void relativizesMavenWindowsPathsThatStartWithSlashBeforeTheDriveLetter() {
        // Maven 在 Windows 上就是这样打印的：`/E:/...`（盘符前多一个斜杠）。
        // 直接丢给 Path.of 会抛 InvalidPathException（Illegal char <:>），
        // 而异常被吞掉的表现是「相对化静默失效」—— 日志里看不出任何异常。
        String console = """
                [ERROR] /E:/work/task-5/src/test/java/com/example/unfixable/LegacyStockLedgerTest.java:[6,26] 程序包com.example.oracle不存在
                """;

        List<String> blocks = MavenResultParser.extractCompileErrors(console, Path.of("E:/work/task-5"), 40);

        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).startsWith("src/test/java/com/example/unfixable/LegacyStockLedgerTest.java:"),
                "Maven 的 /E:/.. 形态也要能相对化，实际: " + blocks.get(0));
        assertFalse(blocks.get(0).contains("task-5"), "临时任务目录名不该留在喂给模型的文本里");
    }

    @Test
    void supportsEnglishDiagnostics() {
        // JDK 18+（JEP 400）起 javac 按 stderr.encoding 输出，英文环境同样是英文诊断
        String english = """
                [ERROR] /repo/src/main/java/Foo.java:[12,34] cannot find symbol
                  symbol:   method bar()
                  location: class Foo
                """;

        List<String> blocks = MavenResultParser.extractCompileErrors(english, Path.of("/repo"), 40);

        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).contains("symbol:") && blocks.get(0).contains("bar()"),
                "英文诊断的 symbol/location 也要保留，实际: " + blocks.get(0));
    }

    @Test
    void capsTotalLinesRatherThanBlockCount() {
        // 上限是「总行数」：一个块占 3 行，所以 4 行预算只能装下 1 个完整块，不会截出半块
        List<String> blocks = MavenResultParser.extractCompileErrors(
                CONSOLE_COMPILE_FAILED, Path.of("/work/task-3"), 4);

        assertEquals(1, blocks.size(), "预算不够装第二个完整块时应当停下，而不是截断，实际: " + blocks);
        assertTrue(blocks.get(0).contains("位置:"), "留下的这一块必须是完整的（含明细续行）");
    }

    @Test
    void returnsEmptyForBlankConsoleOrNonPositiveLimit() {
        assertTrue(MavenResultParser.extractCompileErrors("", Path.of("/repo"), 40).isEmpty());
        assertTrue(MavenResultParser.extractCompileErrors(null, Path.of("/repo"), 40).isEmpty());
        assertTrue(MavenResultParser.extractCompileErrors(CONSOLE_COMPILE_FAILED, Path.of("/repo"), 0).isEmpty());
    }
}
