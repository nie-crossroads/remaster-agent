package com.remasteragent.tools.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Spring3BreakingApiScanner} 的单测 —— 钉住「哪些文件会被判定为需要迁移」。
 *
 * <p>这把尺子的价值在于<b>召回</b>：漏报的代价是整仓编译卡在某个没人动过的文件上
 * （博客工程 {@code RestTemplateConfig.java} 就是这么炸的），因此测试的重点是
 * 「一个 javax 都没有的文件也要能被认出来」，以及「已经现代化的文件不能被误伤」。
 */
class Spring3BreakingApiScannerTest {

    /** 博客工程 RestTemplateConfig 的同类：零 javax，却用了 Spring 6 已换底层实现的 API。 */
    private static final String REST_TEMPLATE_CONFIG = """
            package com.blog.system.config;

            import org.apache.http.impl.client.CloseableHttpClient;
            import org.apache.http.impl.client.HttpClients;
            import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
            import org.springframework.web.client.ResponseErrorHandler;
            import org.springframework.web.client.RestTemplate;

            public class RestTemplateConfig {

                public RestTemplate restTemplate() {
                    CloseableHttpClient httpClient = HttpClients.createDefault();
                    return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
                }

                public ResponseErrorHandler errorHandler() {
                    return new ResponseErrorHandler() {
                        @Override
                        public boolean hasError(ClientHttpResponse response) {
                            return false;
                        }

                        @Override
                        public void handleError(ClientHttpResponse response) {
                        }
                    };
                }
            }
            """;

    private static final String WEBMVC_ADAPTER = """
            package com.example;

            import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

            public class WebConfig extends WebMvcConfigurerAdapter {
            }
            """;

    private static final String ALREADY_MODERN = """
            package com.example;

            import java.time.Instant;

            public class Demo {
                public Instant now() {
                    return Instant.now();
                }
            }
            """;

    @Test
    @DisplayName("HttpComponentsClientHttpRequestFactory：命中（Spring 6 只接受 HttpClient 5）")
    void httpComponentsFactoryIsDetected() {
        List<Spring3BreakingApiScanner.Finding> findings =
                Spring3BreakingApiScanner.scan(REST_TEMPLATE_CONFIG);

        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("SPRING6_HTTPCOMPONENTS_FACTORY")),
                "这是整仓编译卡死的直接原因，必须被认出来: " + findings);
    }

    @Test
    @DisplayName("单参数 handleError(ClientHttpResponse)：命中（Spring 6 已标记 forRemoval）")
    void responseErrorHandlerSingleArgIsDetected() {
        List<Spring3BreakingApiScanner.Finding> findings =
                Spring3BreakingApiScanner.scan(REST_TEMPLATE_CONFIG);

        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("SPRING6_RESPONSE_ERROR_HANDLER")),
                "过时且待删除的覆写必须被认出来: " + findings);
    }

    @Test
    @DisplayName("WebMvcConfigurerAdapter：命中（Spring Boot 3 已删除）")
    void webMvcConfigurerAdapterIsDetected() {
        assertTrue(Spring3BreakingApiScanner.hasAny(WEBMVC_ADAPTER));
    }

    @Test
    @DisplayName("org.apache.http.* 的 import：命中（HttpClient 4 遗留）")
    void httpClient4ImportIsDetected() {
        List<Spring3BreakingApiScanner.Finding> findings =
                Spring3BreakingApiScanner.scan(REST_TEMPLATE_CONFIG);

        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("HTTPCLIENT4_LEGACY")),
                "HttpClient 4 的 import 必须被认出来: " + findings);
    }

    @Test
    @DisplayName("已现代化的文件：零命中 —— 不把干净文件拉进改写清单")
    void modernFileHasNoFindings() {
        assertFalse(Spring3BreakingApiScanner.hasAny(ALREADY_MODERN));
        assertEquals("", Spring3BreakingApiScanner.describe(Spring3BreakingApiScanner.scan(ALREADY_MODERN)));
    }

    @Test
    @DisplayName("describe：给出可直接进 prompt 的改法，而不是只报个名字")
    void describeExplainsHowToFix() {
        String text = Spring3BreakingApiScanner.describe(Spring3BreakingApiScanner.scan(REST_TEMPLATE_CONFIG));

        assertTrue(text.contains("HttpClient 5"), "提示必须说清要换成什么: " + text);
        assertTrue(text.contains("HttpComponentsClientHttpRequestFactory"), "提示应点名命中的类型: " + text);
    }

    @Test
    @DisplayName("空源码 / null：返回空清单，绝不抛异常阻断流程")
    void blankSourceIsSafe() {
        assertTrue(Spring3BreakingApiScanner.scan(null).isEmpty());
        assertTrue(Spring3BreakingApiScanner.scan("   ").isEmpty());
    }
}
