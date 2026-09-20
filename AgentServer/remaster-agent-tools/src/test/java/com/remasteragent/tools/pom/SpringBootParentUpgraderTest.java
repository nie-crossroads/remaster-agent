package com.remasteragent.tools.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringBootParentUpgraderTest {

    private static final String POM_WITH_PARENT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project>\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <parent>\n"
            + "    <groupId>org.springframework.boot</groupId>\n"
            + "    <artifactId>spring-boot-starter-parent</artifactId>\n"
            + "    <version>2.7.17</version>\n"
            + "    <relativePath/>\n"
            + "  </parent>\n"
            + "  <groupId>com.example</groupId>\n"
            + "  <artifactId>demo</artifactId>\n"
            + "  <dependencies></dependencies>\n"
            + "  <build>\n"
            + "    <plugins>\n"
            + "      <plugin>\n"
            + "        <groupId>org.springframework.boot</groupId>\n"
            + "        <artifactId>spring-boot-maven-plugin</artifactId>\n"
            + "        <version>2.7.17</version>\n"
            + "      </plugin>\n"
            + "    </plugins>\n"
            + "  </build>\n"
            + "</project>\n";

    @Test
    void upgradesParentAndPluginVersion() {
        SpringBootParentUpgrader.Result r = SpringBootParentUpgrader.upgrade(POM_WITH_PARENT);
        assertTrue(r.changed());
        assertTrue(r.content().contains(
                "<artifactId>spring-boot-starter-parent</artifactId>\n    <version>3.5.16</version>"));
        assertTrue(r.content().contains(
                "<artifactId>spring-boot-maven-plugin</artifactId>\n        <version>3.5.16</version>"));
        assertEquals(2, r.changes().size());
    }

    @Test
    void needsUpgradeTrueForPre3() {
        assertTrue(SpringBootParentUpgrader.needsUpgrade(POM_WITH_PARENT));
    }

    @Test
    void idempotentWhenAlreadyTarget() {
        SpringBootParentUpgrader.Result once = SpringBootParentUpgrader.upgrade(POM_WITH_PARENT);
        SpringBootParentUpgrader.Result twice = SpringBootParentUpgrader.upgrade(once.content());
        assertFalse(twice.changed(), "已经是目标版本不应再改");
    }

    @Test
    void noOpWhenNoSpringBootParent() {
        String pom = "<?xml version=\"1.0\"?>\n<project><modelVersion>4.0.0</modelVersion>"
                + "<dependencies></dependencies></project>\n";
        assertFalse(SpringBootParentUpgrader.needsUpgrade(pom));
        SpringBootParentUpgrader.Result r = SpringBootParentUpgrader.upgrade(pom);
        assertFalse(r.changed());
    }

    @Test
    void doesNotUpgradePropertyReferencedVersion() {
        String pom = "<?xml version=\"1.0\"?>\n<project>\n"
                + "  <parent>\n"
                + "    <groupId>org.springframework.boot</groupId>\n"
                + "    <artifactId>spring-boot-starter-parent</artifactId>\n"
                + "    <version>${spring.boot.version}</version>\n"
                + "  </parent>\n"
                + "</project>\n";
        assertFalse(SpringBootParentUpgrader.needsUpgrade(pom), "属性引用的版本不应自动升级");
    }

    @Test
    void noOpWhenAlready3x() {
        String pom = "<?xml version=\"1.0\"?>\n<project>\n"
                + "  <parent>\n"
                + "    <groupId>org.springframework.boot</groupId>\n"
                + "    <artifactId>spring-boot-starter-parent</artifactId>\n"
                + "    <version>3.2.0</version>\n"
                + "  </parent>\n"
                + "</project>\n";
        assertFalse(SpringBootParentUpgrader.needsUpgrade(pom));
    }

    @Test
    void rejectsMalformedPom() {
        assertThrows(IllegalArgumentException.class,
                () -> SpringBootParentUpgrader.upgrade("<project><parent><version>2.7"));
    }
}
