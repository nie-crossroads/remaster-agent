package com.remasteragent.tools.pom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PomJavaVersionRewriter} 的确定性单测 —— 不依赖 LLM / DB / Maven。
 *
 * <p>这条路径值得被钉死，因为它是「整仓 JDK 升级」的第一步：编译级别没抬上去，
 * 后面每个文件的 record / 文本块都会被 javac 拒收，表现为「模型改对了但编译不过」，
 * 而失败反馈会把模型引向错误的修复方向。
 */
class PomJavaVersionRewriterTest {

    /** 一份典型的 Java 8 工程 pom（带解释性注释，正是最容易被改写器抹掉的东西）。 */
    private static final String POM_RELEASE_8 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>legacy-module</artifactId>
              <version>1.0.0</version>
              <properties>
                <!-- 历史包袱：release 8 而不是 11，因为老机器上只有 JDK 8 -->
                <maven.compiler.release>8</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    @DisplayName("属性式的 release=8 → 改成目标值，且只动那一个数字")
    void upgradesReleasePropertyWithMinimalDiff() {
        PomJavaVersionRewriter.Result result = PomJavaVersionRewriter.rewrite(POM_RELEASE_8, 21);

        assertTrue(result.changed());
        assertTrue(result.content().contains("<maven.compiler.release>21</maven.compiler.release>"));
        // 注释必须原样保留 —— 这是不用 DOM 的首要理由
        assertTrue(result.content().contains("历史包袱：release 8 而不是 11"));
        // 其它属性一个字节都不动
        assertTrue(result.content().contains(
                "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>"));
        assertEquals(1, result.changes().size());
        assertEquals("maven.compiler.release: 8 → 21", result.changes().get(0));
    }

    @Test
    @DisplayName("source/target 是 1.8 这种老写法：也认得出来并归一成目标值")
    void upgradesLegacySourceTargetNotation() {
        String pom = """
                <project>
                  <properties>
                    <maven.compiler.source>1.8</maven.compiler.source>
                    <maven.compiler.target>1.8</maven.compiler.target>
                  </properties>
                </project>
                """;

        assertTrue(PomJavaVersionRewriter.needsUpgrade(pom, 21));
        PomJavaVersionRewriter.Result result = PomJavaVersionRewriter.rewrite(pom, 21);

        assertTrue(result.content().contains("<maven.compiler.source>21</maven.compiler.source>"));
        assertTrue(result.content().contains("<maven.compiler.target>21</maven.compiler.target>"));
        assertEquals(2, result.changes().size());
    }

    /**
     * 这份 pom 的形状抄自真实样例工程 {@code examples/eval/multimodule-legacy/pom.xml} ——
     * 老式三元组：一个 {@code java.version} 属性被三处 {@code ${...}} 引用
     * （两处编译属性 + 两处 plugin configuration）。
     *
     * <p>它是「整仓 JDK 升级」最容易出错的一种形态：值藏在间接层后面。
     * 改写器必须做到三件事——① 判「要不要升」时能穿透引用看到 1.8；
     * ② 只改属性值那一处、把引用原样留给 Maven 解析；③ 把「这三处跳过了」如实说出来，
     * 而不是默默不动、让报告显示成「无需升级」。
     */
    @Test
    @DisplayName("间接层写法（java.version 属性 + 多处 ${} 引用）：只改属性值，引用原样保留")
    void upgradesJavaVersionPropertyAndKeepsReferences() {
        String pom = """
                <project>
                  <properties>
                    <java.version>1.8</java.version>
                    <maven.compiler.source>${java.version}</maven.compiler.source>
                    <maven.compiler.target>${java.version}</maven.compiler.target>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <source>${java.version}</source>
                          <target>${java.version}</target>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;

        // ① 判升级必须穿透间接层：属性值 1.8 → 8 < 21
        assertTrue(PomJavaVersionRewriter.needsUpgrade(pom, 21));

        PomJavaVersionRewriter.Result result = PomJavaVersionRewriter.rewrite(pom, 21);

        // ② 只改属性值那一处
        assertTrue(result.content().contains("<java.version>21</java.version>"));
        assertFalse(result.content().contains("<java.version>1.8</java.version>"));
        // 三处引用必须原样保留：替换成字面量会把「改一处」变成「以后得改四处」
        assertEquals(4, countOf(result.content(), "${java.version}"));
        // 改完之后不能再声称「还需要升级」，否则整仓升级会被无谓地再跑一遍
        assertFalse(PomJavaVersionRewriter.needsUpgrade(result.content(), 21));

        // ③ 跳过的事要如实说明，不能被静默吞掉
        assertTrue(result.changes().stream().anyMatch(change -> change.contains("不是字面数字")),
                "被跳过的声明必须在 changes 里说明：" + result.changes());
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int cursor = text.indexOf(needle);
        while (cursor >= 0) {
            count++;
            cursor = text.indexOf(needle, cursor + needle.length());
        }
        return count;
    }

    @Test
    @DisplayName("compiler-plugin 的 configuration 也要改：那里写的 source/target 会覆盖属性")
    void upgradesCompilerPluginConfiguration() {        String pom = """
                <project>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <release>8</release>
                          <encoding>UTF-8</encoding>
                        </configuration>
                      </plugin>
                      <plugin>
                        <artifactId>jacoco-maven-plugin</artifactId>
                        <configuration>
                          <source>1.5</source>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;

        assertTrue(PomJavaVersionRewriter.needsUpgrade(pom, 21),
                "属性虽已是 21，但 plugin 配置里还钉着 8，取最小后应判定需要升级");
        PomJavaVersionRewriter.Result result = PomJavaVersionRewriter.rewrite(pom, 21);

        assertTrue(result.content().contains("<release>21</release>"));
        assertFalse(result.content().contains("<release>8</release>"));
        // 别的插件里的 <source> 不是编译级别，绝不能被顺手改掉
        assertTrue(result.content().contains("<source>1.5</source>"));
    }

    @Test
    @DisplayName("一条声明都没有的 pom：插入 maven.compiler.release，而不是当作「已达标」")
    void insertsPropertyWhenNothingDeclared() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>bare</artifactId>
                  <dependencies>
                    <dependency><artifactId>x</artifactId></dependency>
                  </dependencies>
                </project>
                """;

        assertTrue(PomJavaVersionRewriter.needsUpgrade(pom, 21));
        PomJavaVersionRewriter.Result result = PomJavaVersionRewriter.rewrite(pom, 21);

        assertTrue(result.changed());
        assertTrue(result.content().contains("<maven.compiler.release>21</maven.compiler.release>"));
        // 插进去的属性要落在 <dependencies> 之前（人读 pom 的顺序：坐标 → 属性 → 依赖）
        assertTrue(result.content().indexOf("<properties>") < result.content().indexOf("<dependencies>"));
    }

    @Test
    @DisplayName("已经带 <properties> 但没有编译级别：插进那个 properties 块里")
    void insertsIntoExistingPropertiesBlock() {
        String pom = """
                <project>
                  <properties>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <build/>
                </project>
                """;

        PomJavaVersionRewriter.Result result = PomJavaVersionRewriter.rewrite(pom, 21);

        assertTrue(result.content().contains("""
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                            <maven.compiler.release>21</maven.compiler.release>"""),
                "新属性应紧跟已有属性、缩进同级；实际输出=\n" + result.content());
        assertEquals(1, result.changes().size());
        // 原属性必须一字不动
        assertFalse(result.content().contains("release>21</maven.compiler.release>\n    </project>"));
    }

    @Test
    @DisplayName("值不是字面数字（属性引用）：不碰它，也不谎称已达标")
    void skipsNonLiteralValueInsteadOfBreakingIndirection() {
        String pom = """
                <project>
                  <properties>
                    <java.version>8</java.version>
                    <maven.compiler.release>${java.version}</maven.compiler.release>
                  </properties>
                </project>
                """;

        PomJavaVersionRewriter.Result result = PomJavaVersionRewriter.rewrite(pom, 21);

        // ${java.version} 必须原样保留 —— 覆盖它会破坏间接层
        assertTrue(result.content().contains("<maven.compiler.release>${java.version}</maven.compiler.release>"));
        // 间接层背后的那个属性本身被抬到 21，效果等价
        assertTrue(result.content().contains("<java.version>21</java.version>"));
        assertTrue(result.changes().stream().anyMatch(c -> c.contains("不是字面数字")),
                "跳过的项要在说明里出现，否则「为什么没升」无从解释；实际=" + result.changes());
    }

    @Test
    @DisplayName("注释里的同名标签不动：pom 注释常在解释「为什么不是 8」，改了会自相矛盾")
    void leavesCommentedOutTagsAlone() {
        String pom = """
                <project>
                  <properties>
                    <!-- 曾经是 <maven.compiler.release>8</maven.compiler.release>，2024 年抬到 11 -->
                    <maven.compiler.release>11</maven.compiler.release>
                  </properties>
                </project>
                """;

        PomJavaVersionRewriter.Result result = PomJavaVersionRewriter.rewrite(pom, 21);

        assertTrue(result.content().contains("曾经是 <maven.compiler.release>8</maven.compiler.release>"));
        assertTrue(result.content().contains("<maven.compiler.release>21</maven.compiler.release>"));
    }

    @Test
    @DisplayName("达标即无改动：同一个 pom 重复跑是幂等的，不会每轮都产出一个假补丁")
    void idempotentWhenAlreadyAtTarget() {
        PomJavaVersionRewriter.Result first = PomJavaVersionRewriter.rewrite(POM_RELEASE_8, 21);

        assertFalse(PomJavaVersionRewriter.needsUpgrade(first.content(), 21));
        PomJavaVersionRewriter.Result second = PomJavaVersionRewriter.rewrite(first.content(), 21);
        assertFalse(second.changed());
        assertEquals(first.content(), second.content());
        assertTrue(second.changes().isEmpty());
    }

    @Test
    @DisplayName("非法 XML 直接拒绝：不做「尽力而为」的文本手术")
    void refusesMalformedXml() {
        String broken = """
                <project>
                  <properties>
                    <maven.compiler.release>8</maven.compiler.release>
                </project>
                """;

        assertThrows(IllegalArgumentException.class, () -> PomJavaVersionRewriter.rewrite(broken, 21));
    }

    @Test
    @DisplayName("1.8 记 8、21 记 21；${x} 与空值都不算数")
    void parsesLevels() {
        assertEquals(8, PomJavaVersionRewriter.parseLevel("1.8"));
        assertEquals(17, PomJavaVersionRewriter.parseLevel(" 17 "));
        assertEquals(21, PomJavaVersionRewriter.parseLevel("21"));
        assertEquals(null, PomJavaVersionRewriter.parseLevel("${java.version}"));
        assertEquals(null, PomJavaVersionRewriter.parseLevel(""));
    }
}
