package com.remasteragent.core.engine.node;

import com.remasteragent.tools.ast.JdkRemovalScanner;
import com.remasteragent.tools.ast.Spring3BreakingApiScanner;
import com.remasteragent.tools.pom.SpringBoot3DependencyCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「确定性安全网」：模型漏掉含 JDK 已移除 import 的文件时，规则强制补回，
 * 否则 VERIFY 会因整库编译失败而挂在模型根本没打算改的文件上。
 */
class PlanNodeMergeRiskTest {

    @Test
    void modelMissesRiskFile_getsForceAdded() {
        // 模型只挑了已现代化的 DateUtil，漏掉了真正的痛点文件
        List<String> proposed = List.of("src/main/java/com/blog/common/util/DateUtil.java");
        JdkRemovalScanner.ProjectRemovalRisks risks = new JdkRemovalScanner.ProjectRemovalRisks(Map.of(
                "src/main/java/com/blog/service/ArticleService.java",
                List.of(new JdkRemovalScanner.RemovalRisk("javax.annotation.Resource", 11, "jakarta.annotation", "x")),
                "src/main/java/com/blog/system/handler/AddMessageHandler.java",
                List.of(new JdkRemovalScanner.RemovalRisk("javax.xml.ws.RequestWrapper", 11, "jakarta.xml.ws", "x"))
        ));

        List<String> merged = PlanNode.mergeRiskFiles(proposed, risks);

        // DateUtil 保序在前，两个风险文件被强制补入
        assertEquals(3, merged.size());
        assertEquals("src/main/java/com/blog/common/util/DateUtil.java", merged.get(0));
        assertTrue(merged.contains("src/main/java/com/blog/service/ArticleService.java"));
        assertTrue(merged.contains("src/main/java/com/blog/system/handler/AddMessageHandler.java"));
    }

    @Test
    void modelAlreadyPickedRiskFile_isNotDuplicated() {
        List<String> proposed = List.of(
                "src/main/java/com/blog/service/ArticleService.java",
                "src/main/java/com/blog/common/util/DateUtil.java");
        JdkRemovalScanner.ProjectRemovalRisks risks = new JdkRemovalScanner.ProjectRemovalRisks(Map.of(
                "src/main/java/com/blog/service/ArticleService.java",
                List.of(new JdkRemovalScanner.RemovalRisk("javax.annotation.Resource", 11, "jakarta.annotation", "x"))
        ));

        List<String> merged = PlanNode.mergeRiskFiles(proposed, risks);
        assertEquals(2, merged.size(), "已选中的风险文件不应被重复加入");
    }

    @Test
    void backslashPath_isNormalizedWhenComparing() {
        // 模型给的是 Windows 反斜杠路径，风险地图是正斜杠，比较时应归一化
        List<String> proposed = List.of("src\\main\\java\\com\\blog\\common\\util\\DateUtil.java");
        JdkRemovalScanner.ProjectRemovalRisks risks = new JdkRemovalScanner.ProjectRemovalRisks(Map.of(
                "src/main/java/com/blog/service/ArticleService.java",
                List.of(new JdkRemovalScanner.RemovalRisk("javax.annotation.Resource", 11, "jakarta.annotation", "x"))
        ));
        List<String> merged = PlanNode.mergeRiskFiles(proposed, risks);
        assertEquals(2, merged.size());
        assertTrue(merged.contains("src/main/java/com/blog/service/ArticleService.java"));
    }

    @Test
    void modelMissesSpring3BreakingApiFile_getsForceAdded() {
        // 模型挑了已现代化的工具类，漏掉了零 javax、但用了 Spring 6 换掉底层实现的 API 的配置类
        List<String> proposed = List.of("src/main/java/com/blog/common/util/DateUtil.java");
        Map<String, List<Spring3BreakingApiScanner.Finding>> byFile = Map.of(
                "src/main/java/com/blog/system/config/RestTemplateConfig.java",
                List.of(new Spring3BreakingApiScanner.Finding("SPRING6_HTTPCOMPONENTS_FACTORY",
                        "HttpComponentsClientHttpRequestFactory", "改用 HttpClient 5")));

        List<String> merged = PlanNode.mergeBreakingApiFiles(proposed, byFile);

        assertEquals(2, merged.size());
        assertTrue(merged.contains("src/main/java/com/blog/system/config/RestTemplateConfig.java"),
                "一个 javax 都没有、却会被 Spring 6 卡住编译的文件必须被强制补进清单: " + merged);
    }

    @Test
    void spring3BreakingApiHit_requiresDependencyInjection() {
        Map<String, List<Spring3BreakingApiScanner.Finding>> byFile = Map.of(
                "src/main/java/com/blog/system/config/RestTemplateConfig.java",
                List.of(new Spring3BreakingApiScanner.Finding("SPRING6_HTTPCOMPONENTS_FACTORY",
                        "HttpComponentsClientHttpRequestFactory", "改用 HttpClient 5")));

        assertTrue(SpringBoot3DependencyCatalog.anyNeeded(byFile),
                "底层库被换掉属于依赖缺口，改源码解决不了，必须触发 DEPENDENCY_UPGRADE 注入 httpclient5");
    }

    @Test
    void cleanProject_isNotDraggedIntoPlanOrDependencyInjection() {
        Map<String, List<Spring3BreakingApiScanner.Finding>> empty = Map.of();

        assertEquals(1, PlanNode.mergeBreakingApiFiles(List.of("a.java"), empty).size());
        assertFalse(SpringBoot3DependencyCatalog.anyNeeded(empty),
                "没有命中时既不该补文件，也不该插依赖升级节点");
    }
}
