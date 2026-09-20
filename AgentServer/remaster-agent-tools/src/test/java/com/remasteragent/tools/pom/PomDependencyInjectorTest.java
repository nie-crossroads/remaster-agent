package com.remasteragent.tools.pom;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomDependencyInjectorTest {

    private static final JakartaArtifactCatalog.Artifact ANNOTATION =
            new JakartaArtifactCatalog.Artifact("jakarta.annotation", "jakarta.annotation-api", "2.1.1", "provided");
    private static final JakartaArtifactCatalog.Artifact SERVLET =
            new JakartaArtifactCatalog.Artifact("jakarta.servlet", "jakarta.servlet-api", "6.0.0", "provided");
    private static final JakartaArtifactCatalog.Artifact MAIL =
            new JakartaArtifactCatalog.Artifact("com.sun.mail", "jakarta.mail", "2.0.1", "compile");

    private static final String POM_WITH_DEPS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project>\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <dependencies>\n"
            + "    <dependency>\n"
            + "      <groupId>com.example</groupId>\n"
            + "      <artifactId>existing</artifactId>\n"
            + "      <version>1.0</version>\n"
            + "    </dependency>\n"
            + "  </dependencies>\n"
            + "</project>\n";

    private static final String POM_WITHOUT_DEPS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project>\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <build>\n"
            + "    <plugins/>\n"
            + "  </build>\n"
            + "</project>\n";

    @Test
    void injectsMissingDependencyIntoExistingDependenciesBlock() {
        PomDependencyInjector.Result r = PomDependencyInjector.inject(
                POM_WITH_DEPS, Set.of(ANNOTATION));
        assertTrue(r.changed());
        assertTrue(r.content().contains("jakarta.annotation-api"));
        assertTrue(r.content().contains("<scope>provided</scope>"));
        // 原有依赖必须原封不动保留
        assertTrue(r.content().contains("com.example"));
        assertTrue(r.content().contains("</dependencies>"));
        assertEquals(List.of(ANNOTATION.coords()), r.added());
    }

    @Test
    void injectsMultipleDependenciesInDeterministicOrder() {
        PomDependencyInjector.Result r = PomDependencyInjector.inject(
                POM_WITH_DEPS, Set.of(MAIL, ANNOTATION, SERVLET));
        assertTrue(r.changed());
        // compile 作用域不该出现 <scope>
        assertTrue(r.content().contains("<artifactId>jakarta.mail</artifactId>"));
        assertFalse(r.content().contains("<artifactId>jakarta.mail</artifactId>\n      <version>2.0.1</version>\n      <scope>"),
                "compile 作用域不应出现 <scope> 标签");
        assertTrue(r.content().contains("jakarta.servlet-api"));
    }

    @Test
    void isIdempotentWhenDependencyAlreadyPresent() {
        PomDependencyInjector.Result first = PomDependencyInjector.inject(POM_WITH_DEPS, Set.of(ANNOTATION));
        PomDependencyInjector.Result second = PomDependencyInjector.inject(first.content(), Set.of(ANNOTATION));
        assertFalse(second.changed(), "第二次注入同样的依赖不应再改动");
        // 只出现一次
        assertEquals(1, countOccurrences(second.content(), "jakarta.annotation-api"));
    }

    @Test
    void doesNotDuplicateExistingDependency() {
        String withDep = PomDependencyInjector.inject(POM_WITH_DEPS, Set.of(ANNOTATION)).content();
        PomDependencyInjector.Result r = PomDependencyInjector.inject(withDep, Set.of(ANNOTATION));
        assertFalse(r.changed());
    }

    @Test
    void createsDependenciesBlockWhenAbsent() {
        PomDependencyInjector.Result r = PomDependencyInjector.inject(POM_WITHOUT_DEPS, Set.of(ANNOTATION));
        assertTrue(r.changed());
        assertTrue(r.content().contains("<dependencies>"));
        assertTrue(r.content().contains("jakarta.annotation-api"));
        // 新建的块应插在 <build> 之前
        int depsIdx = r.content().indexOf("<dependencies>");
        int buildIdx = r.content().indexOf("<build");
        assertTrue(depsIdx >= 0 && buildIdx >= 0 && depsIdx < buildIdx, "依赖块应在 build 之前");
    }

    @Test
    void rejectsMalformedPom() {
        assertThrows(IllegalArgumentException.class,
                () -> PomDependencyInjector.inject("<project><broken>", Set.of(ANNOTATION)));
    }

    @Test
    void noOpWhenNoArtifactsNeeded() {
        PomDependencyInjector.Result r = PomDependencyInjector.inject(POM_WITH_DEPS, Set.of());
        assertFalse(r.changed());
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
