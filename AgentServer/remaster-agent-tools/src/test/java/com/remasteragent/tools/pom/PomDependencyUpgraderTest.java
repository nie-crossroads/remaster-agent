package com.remasteragent.tools.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomDependencyUpgraderTest {

    private static final String POM_HEAD =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
            "  <modelVersion>4.0.0</modelVersion>\n" +
            "  <dependencies>\n";
    private static final String POM_TAIL = "  </dependencies>\n</project>\n";

    private static String pom(String... deps) {
        return POM_HEAD + String.join("\n", deps) + "\n" + POM_TAIL;
    }

    private static String dep(String group, String art, String version, String scope) {
        return "    <dependency>\n" +
                "      <groupId>" + group + "</groupId>\n" +
                "      <artifactId>" + art + "</artifactId>\n" +
                "      <version>" + version + "</version>\n" +
                (scope == null ? "" : "      <scope>" + scope + "</scope>\n") +
                "    </dependency>";
    }

    @Test
    void renamesMysqlConnectorCoordinateKeepingVersionAndScope() {
        String xml = pom(dep("mysql", "mysql-connector-java", "8.0.33", "runtime"));
        PomDependencyUpgrader.Result r = PomDependencyUpgrader.upgrade(xml);

        assertTrue(r.changed());
        assertTrue(r.content().contains("<groupId>com.mysql</groupId>"));
        assertTrue(r.content().contains("<artifactId>mysql-connector-j</artifactId>"));
        assertTrue(r.content().contains("<version>8.0.33</version>"), "版本应保留原声明值");
        assertTrue(r.content().contains("<scope>runtime</scope>"), "scope 应保留");
        assertFalse(r.content().contains("mysql-connector-java"), "旧坐标不应残留");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("rename mysql:mysql-connector-java")));
    }

    @Test
    void bumpsMybatisStarterTo30x() {
        String xml = pom(dep("org.mybatis.spring.boot", "mybatis-spring-boot-starter", "2.3.1", null));
        PomDependencyUpgrader.Result r = PomDependencyUpgrader.upgrade(xml);

        assertTrue(r.changed());
        assertTrue(r.content().contains("<version>3.0.3</version>"), "mybatis 必须升到 3.0.3");
        assertFalse(r.content().contains("2.3.1"), "旧版本不应残留");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("bump")));
    }

    @Test
    void leavesUnrelatedDependenciesUntouched() {
        String xml = pom(
                dep("org.springframework.boot", "spring-boot-starter-web", "3.5.16", null),
                dep("com.github.pagehelper", "pagehelper-spring-boot-starter", "2.0.0", null));
        PomDependencyUpgrader.Result r = PomDependencyUpgrader.upgrade(xml);

        assertFalse(r.changed(), "pagehelper 2.0.0 已是 SB3 线，不应改动");
    }

    @Test
    void idempotentOnAlreadyUpgradedPom() {
        String once = pom(dep("mysql", "mysql-connector-j", "8.0.33", "runtime"));
        PomDependencyUpgrader.Result r = PomDependencyUpgrader.upgrade(once);
        assertFalse(r.changed(), "已经是新坐标，不应二次改动");
    }

    @Test
    void doesNotTouchParentBlock() {
        String xml = "<?xml version=\"1.0\"?>\n<project>\n" +
                "  <parent>\n    <groupId>org.springframework.boot</groupId>\n" +
                "    <artifactId>spring-boot-starter-parent</artifactId>\n    <version>2.7.17</version>\n  </parent>\n" +
                "  <dependencies>\n" + dep("mysql", "mysql-connector-java", "8.0.33", "runtime") + "\n  </dependencies>\n</project>\n";
        PomDependencyUpgrader.Result r = PomDependencyUpgrader.upgrade(xml);

        assertTrue(r.changed());
        assertTrue(r.content().contains("<version>2.7.17</version>"), "parent 版本不应被改写");
        assertTrue(r.content().contains("mysql-connector-j"), "依赖坐标应被改写");
    }

    @Test
    void anyUpgradeNeededDetectsOldCoordinates() {
        assertTrue(PomDependencyUpgrader.anyUpgradeNeeded(
                pom(dep("mysql", "mysql-connector-java", "8.0.33", "runtime"))));
        assertTrue(PomDependencyUpgrader.anyUpgradeNeeded(
                pom(dep("org.mybatis.spring.boot", "mybatis-spring-boot-starter", "2.3.1", null))));
        assertFalse(PomDependencyUpgrader.anyUpgradeNeeded(
                pom(dep("com.github.pagehelper", "pagehelper-spring-boot-starter", "2.0.0", null))));
    }

    @Test
    void rejectsMalformedXml() {
        assertThrows(IllegalArgumentException.class,
                () -> PomDependencyUpgrader.upgrade("<project><dependencies>"));
    }
}
