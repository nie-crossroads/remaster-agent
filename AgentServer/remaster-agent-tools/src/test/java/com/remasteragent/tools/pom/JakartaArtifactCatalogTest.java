package com.remasteragent.tools.pom;

import com.remasteragent.tools.ast.JdkRemovalScanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JakartaArtifactCatalogTest {

    @Test
    void mapsRemovedJavaxAnnotationToJakartaArtifact() {
        Optional<JakartaArtifactCatalog.Artifact> a =
                JakartaArtifactCatalog.forImport("javax.annotation.Resource");
        assertTrue(a.isPresent());
        assertEquals("jakarta.annotation", a.get().groupId());
        assertEquals("jakarta.annotation-api", a.get().artifactId());
        assertEquals("2.1.1", a.get().version());
        assertEquals("provided", a.get().scope());
    }

    @Test
    void mapsServletSubpackageByLongestPrefix() {
        Optional<JakartaArtifactCatalog.Artifact> a =
                JakartaArtifactCatalog.forImport("javax.servlet.http.HttpServletRequest");
        assertTrue(a.isPresent());
        assertEquals("jakarta.servlet-api", a.get().artifactId());
        assertEquals("6.0.0", a.get().version());
    }

    @Test
    void mapsJaxbAndSoapToDistinctArtifacts() {
        assertEquals("jakarta.xml.bind-api",
                JakartaArtifactCatalog.forImport("javax.xml.bind.JAXBContext").orElseThrow().artifactId());
        assertEquals("jakarta.xml.soap-api",
                JakartaArtifactCatalog.forImport("javax.xml.soap.SOAPMessage").orElseThrow().artifactId());
        assertEquals("jakarta.xml.ws-api",
                JakartaArtifactCatalog.forImport("javax.xml.ws.Service").orElseThrow().artifactId());
    }

    @Test
    void excludesPackagesStillInJdk() {
        // 这些仍在 JDK 里，绝不能映射成 jakarta 依赖（全局替换会把它们误伤）
        for (String fqn : List.of(
                "javax.annotation.processing.Processor",
                "javax.annotation.meta.When",
                "javax.crypto.Cipher",
                "javax.sql.DataSource",
                "javax.naming.Context",
                "javax.xml.parsers.DocumentBuilderFactory",
                "javax.script.ScriptEngine")) {
            assertFalse(JakartaArtifactCatalog.forImport(fqn).isPresent(),
                    "不应映射仍在 JDK 的包: " + fqn);
        }
    }

    @Test
    void returnsEmptyForPackagesWithNoJakartaReplacement() {
        // 整个被移除、且没有任何 Jakarta 替代坐标的包
        for (String fqn : List.of("javax.xml.rpc.Service", "org.omg.CORBA.Object", "com.sun.corba.AnyImpl")) {
            assertFalse(JakartaArtifactCatalog.forImport(fqn).isPresent(),
                    "不应为无替代的移除包注入依赖: " + fqn);
        }
    }

    @Test
    void artifactsForImportsDeduplicatesAndSkipsNonMapping() {
        Set<JakartaArtifactCatalog.Artifact> result = JakartaArtifactCatalog.artifactsForImports(List.of(
                "javax.annotation.Resource",
                "javax.annotation.PostConstruct",
                "javax.xml.rpc.Service"));
        assertEquals(1, result.size());
        assertEquals("jakarta.annotation-api", result.iterator().next().artifactId());
    }

    @Test
    void anyArtifactNeededTrueWhenRemovalRiskMapsToArtifact() {
        JdkRemovalScanner.ProjectRemovalRisks risks = new JdkRemovalScanner.ProjectRemovalRisks(Map.of(
                "src/main/java/com/example/Foo.java",
                List.of(new JdkRemovalScanner.RemovalRisk("javax.annotation.Resource", 11,
                        "jakarta.annotation", "note"))));
        assertTrue(JakartaArtifactCatalog.anyArtifactNeeded(risks));
    }

    @Test
    void anyArtifactNeededFalseForNonMappableRisk() {
        JdkRemovalScanner.ProjectRemovalRisks risks = new JdkRemovalScanner.ProjectRemovalRisks(Map.of(
                "src/main/java/com/example/Foo.java",
                List.of(new JdkRemovalScanner.RemovalRisk("javax.xml.rpc.Service", 11,
                        null, "note"))));
        assertFalse(JakartaArtifactCatalog.anyArtifactNeeded(risks));
    }
}
