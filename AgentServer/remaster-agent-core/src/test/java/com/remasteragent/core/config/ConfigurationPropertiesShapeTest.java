package com.remasteragent.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置类的「形状」约束 —— 守护一件编译期看不出来、单测也看不出来、只有真启动时才炸的事。
 *
 * <h2>为什么需要这个测试</h2>
 * <p>{@code @ConfigurationProperties} 对 <b>record</b> 走的是「构造器绑定」，而 Spring 只接受
 * <b>唯一</b>的声明构造器。一旦给 record 多写一个构造器（例如为了少传一个参数而加个便捷重载），
 * 绑定会静默退化成 JavaBean 方式，转而去要一个无参构造 —— record 没有无参构造，于是
 * 应用<b>启动直接失败</b>：
 *
 * <pre>
 *   Failed to instantiate [com.remasteragent.core.config.CoreProperties]:
 *   No default constructor found
 * </pre>
 *
 * <p>这个缺陷的形状很恶劣：<b>编译通过、全部单测通过、构建成功</b>，只在真正启动进程时暴露。
 * 本项目实测踩过一次（给 {@code CoreProperties} 加门禁超时参数时顺手加了个 8 参便捷构造），
 * 而当时整个代码库<b>没有任何 {@code @SpringBootTest}</b> —— 配置绑定从来不在测试覆盖范围内。
 *
 * <p>与其引入一个要启动整个容器的重测试（慢，且依赖真实 DB / Redis），不如用反射直接断言形状：
 * 便宜、确定、精确命中根因。要表达「少传一个参数」的便利，请用<b>静态工厂</b> ——
 * 它不出现在 {@code getDeclaredConstructors()} 里，因此不会干扰绑定。
 *
 * <p>扫描用的是 classpath 元数据读取（而不是硬编码类清单）：将来新加一个配置类，
 * 无需改这个测试就会被纳入检查。
 */
class ConfigurationPropertiesShapeTest {

    /** core 及其依赖模块（common / llm / tools）里的配置类数量。数字变了说明有新增或漏扫。 */
    private static final int EXPECTED_MIN_PROPERTIES_CLASSES = 6;

    @Test
    @DisplayName("每个 @ConfigurationProperties 类都必须是 Spring 能绑定的形状")
    void propertiesClassesAreBindable() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        SimpleMetadataReaderFactory metadataFactory = new SimpleMetadataReaderFactory();
        Resource[] resources = resolver.getResources("classpath*:com/remasteragent/**/*.class");

        List<String> violations = new ArrayList<>();
        int scanned = 0;

        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            var metadataReader = metadataFactory.getMetadataReader(resource);
            var annotations = metadataReader.getAnnotationMetadata();
            if (!annotations.hasAnnotation(ConfigurationProperties.class.getName())) {
                continue;
            }

            String className = metadataReader.getClassMetadata().getClassName();
            scanned++;
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (Throwable e) {
                // 不静默跳过：加载不了就意味着这个类根本没被检查到，那正是这个测试最该报的错
                violations.add(className + " 无法加载，未能校验: " + e);
                continue;
            }

            var constructors = type.getDeclaredConstructors();
            if (type.isRecord()) {
                if (constructors.length != 1) {
                    violations.add(className + " 是 record，却有 " + constructors.length
                            + " 个声明构造器 —— Spring 会因此退化成 JavaBean 绑定、"
                            + "转去找无参构造，导致应用启动失败。请改用静态工厂承载便捷重载");
                }
            } else if (java.util.Arrays.stream(constructors)
                    .noneMatch(c -> c.getParameterCount() == 0)) {
                violations.add(className + " 不是 record，又没有无参构造 —— "
                        + "JavaBean 绑定同样起不来");
            }
        }

        assertTrue(scanned >= EXPECTED_MIN_PROPERTIES_CLASSES,
                "只扫到 " + scanned + " 个 @ConfigurationProperties 类（预期至少 "
                        + EXPECTED_MIN_PROPERTIES_CLASSES + "）—— classpath 扫描本身失效了，"
                        + "此时这个测试是「假绿」，必须先修扫描");
        assertEquals(List.of(), violations,
                "配置类形状不合规，这些类会让应用在启动时（而不是编译时）失败: " + violations);
    }
}
