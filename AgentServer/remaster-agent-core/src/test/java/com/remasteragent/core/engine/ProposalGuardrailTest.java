package com.remasteragent.core.engine;

import com.remasteragent.common.agent.RewriteProposal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产出护栏的单测。
 *
 * <p>护栏是「LLM 的不确定性」与「Java 工程的确定性」之间的那道闸门。它拦不住的后果很具体：
 * 模型换了类名 → 所有引用它的文件一起编译失败，而失败原因看起来像是「沙箱有问题」；
 * 模型返回半截文件 → 在沙箱里报一堆找不到符号，白白烧掉一次几十秒的构建。
 *
 * <p>所以这里逐条把四种判据钉死。全部是纯文本判定，不依赖模型、不依赖 Maven。
 */
class ProposalGuardrailTest {

    private static final String VALID_SOURCE = """
            package com.example;

            public class Demo {
                public String hi() {
                    return "hi";
                }
            }
            """;

    @Test
    @DisplayName("合规产出：放行")
    void validProposalPasses() {
        ProposalGuardrail.GuardrailResult result = ProposalGuardrail.check(
                "com.example", "Demo", new RewriteProposal("src/Demo.java", VALID_SOURCE, "改用文本块"));

        assertTrue(result.passed(), "合规产出应放行，实际被拒: " + result.reason());
        assertTrue(result.reason().isEmpty(), "通过时不应带原因");
    }

    @Test
    @DisplayName("返回 null：拒绝（不抛 NPE）")
    void nullProposalRejected() {
        ProposalGuardrail.GuardrailResult result = ProposalGuardrail.check("com.example", "Demo", null);

        assertFalse(result.passed());
        assertTrue(result.reason().contains("为空"), "原因应可读: " + result.reason());
    }

    @Test
    @DisplayName("内容为空或纯空白：拒绝")
    void blankContentRejected() {
        assertFalse(ProposalGuardrail.check("com.example", "Demo",
                new RewriteProposal("src/Demo.java", "", "r")).passed());
        assertFalse(ProposalGuardrail.check("com.example", "Demo",
                new RewriteProposal("src/Demo.java", "   \n\t ", "r")).passed());
    }

    @Test
    @DisplayName("不是合法 Java（比如模型回了段解释文字）：拒绝")
    void nonJavaContentRejected() {
        ProposalGuardrail.GuardrailResult result = ProposalGuardrail.check("com.example", "Demo",
                new RewriteProposal("src/Demo.java",
                        "我把这个方法改成了使用文本块，效果更好。", "解释性回复"));

        assertFalse(result.passed(), "自然语言回复必须被拦下，否则闭环退化成人工复制粘贴");
        assertTrue(result.reason().contains("解析"), "原因应指向解析失败: " + result.reason());
    }

    @Test
    @DisplayName("括号不配平的半截文件：拒绝")
    void truncatedSourceRejected() {
        ProposalGuardrail.GuardrailResult result = ProposalGuardrail.check("com.example", "Demo",
                new RewriteProposal("src/Demo.java", "package com.example;\npublic class Demo {\n  void m() {", "r"));

        assertFalse(result.passed());
    }

    @Test
    @DisplayName("包名被改动：拒绝，且原因里同时给出期望值与实际值")
    void packageChangedRejected() {
        String source = "package com.wrong;\n\npublic class Demo {\n}\n";

        ProposalGuardrail.GuardrailResult result = ProposalGuardrail.check(
                "com.example", "Demo", new RewriteProposal("src/Demo.java", source, "顺手整理了包名"));

        assertFalse(result.passed());
        assertTrue(result.reason().contains("com.wrong") && result.reason().contains("com.example"),
                "原因要能直接喂回给模型，故必须包含期望值与实际值: " + result.reason());
    }

    @Test
    @DisplayName("主类型名被改动：拒绝")
    void typeNameChangedRejected() {
        String source = "package com.example;\n\npublic class Renamed {\n}\n";

        ProposalGuardrail.GuardrailResult result = ProposalGuardrail.check(
                "com.example", "Demo", new RewriteProposal("src/Demo.java", source, "重命名了类"));

        assertFalse(result.passed());
        assertTrue(result.reason().contains("Renamed") && result.reason().contains("Demo"),
                "原因要包含期望与实际类名: " + result.reason());
    }

    @Test
    @DisplayName("record / enum / interface 作为主类型同样成立")
    void recordEnumInterfaceAccepted() {
        assertTrue(ProposalGuardrail.check("com.example", "Point",
                new RewriteProposal("src/Point.java", "package com.example;\n\npublic record Point(int x, int y) {\n}\n", "r"))
                .passed());

        assertTrue(ProposalGuardrail.check("com.example", "Level",
                new RewriteProposal("src/Level.java", "package com.example;\n\npublic enum Level { LOW, HIGH }\n", "r"))
                .passed());

        assertTrue(ProposalGuardrail.check("com.example", "Handler",
                new RewriteProposal("src/Handler.java", "package com.example;\n\npublic interface Handler {\n}\n", "r"))
                .passed());
    }

    @Test
    @DisplayName("注释与 import 不影响判定")
    void commentsAndImportsDoNotConfuseIt() {
        String source = """
                // 迁移说明：改用 java.time
                package com.example;

                import java.time.Instant;

                /* 块注释里写 class FakeName 也不该被误认为主类型 */
                public class Demo {
                    private Instant at;
                }
                """;

        assertTrue(ProposalGuardrail.check("com.example", "Demo",
                new RewriteProposal("src/Demo.java", source, "引入 java.time")).passed(),
                "注释里的 class 字样不应被当成主类型 —— 这正是不能用正则匹配的原因");
    }

    @Test
    @DisplayName("默认包（期望包名为空）不做包名校验")
    void defaultPackageIsNotChecked() {
        assertTrue(ProposalGuardrail.check("", "Demo",
                new RewriteProposal("Demo.java", "public class Demo {\n}\n", "r")).passed());
    }

    @Test
    @DisplayName("源文件没有顶层类型（期望类型名为空）不做类型名校验")
    void blankExpectedTypeIsNotChecked() {
        assertTrue(ProposalGuardrail.check("com.example", "",
                new RewriteProposal("package-info.java", "package com.example;\n", "r")).passed());
    }

    private static final String SOURCE_WITH_PUBLIC_MEMBERS = """
            package com.example;

            public class Demo {
                public static final int version = 1;
                public String hi() {
                    return "hi";
                }
                private String secret() {
                    return "x";
                }
            }
            """;

    @Test
    @DisplayName("公开方法被改写删除：拒绝，且原因列出被删成员")
    void removedPublicMethodRejected() {
        String after = """
                package com.example;

                public class Demo {
                    public static final int version = 1;
                }
                """;
        List<String> expected = List.of("com.example.Demo#hi()", "com.example.Demo#version");
        ProposalGuardrail.GuardrailResult result = ProposalGuardrail.check(
                "com.example", "Demo", expected,
                new RewriteProposal("src/Demo.java", after, "顺手删了个没用的方法"));

        assertFalse(result.passed(), "删 public 方法会让调用方/测试编译失败，必须拦下");
        assertTrue(result.reason().contains("Demo#hi()"), "原因要列出被删成员: " + result.reason());
    }

    @Test
    @DisplayName("公开字段被改写删除：拒绝")
    void removedPublicFieldRejected() {
        String after = """
                package com.example;

                public class Demo {
                    public String hi() {
                        return "hi";
                    }
                }
                """;
        List<String> expected = List.of("com.example.Demo#hi()", "com.example.Demo#version");
        ProposalGuardrail.GuardrailResult result = ProposalGuardrail.check(
                "com.example", "Demo", expected,
                new RewriteProposal("src/Demo.java", after, "r"));

        assertFalse(result.passed());
        assertTrue(result.reason().contains("Demo#version"), "原因要列出被删的公开字段: " + result.reason());
    }

    @Test
    @DisplayName("只删 private 成员：放行（private 不影响外部调用方）")
    void removedPrivateMemberOk() {
        String after = """
                package com.example;

                public class Demo {
                    public static final int version = 1;
                    public String hi() {
                        return "hi";
                    }
                }
                """;
        List<String> expected = List.of("com.example.Demo#hi()", "com.example.Demo#version");
        assertTrue(ProposalGuardrail.check("com.example", "Demo", expected,
                new RewriteProposal("src/Demo.java", after, "r")).passed(),
                "删除 private 成员不该被判违规");
    }

    @Test
    @DisplayName("公开成员全部保留（仅改实现）：放行")
    void publicMembersPreservedOk() {
        String after = """
                package com.example;

                public class Demo {
                    public static final int version = 2;
                    public String hi() {
                        return "hello".formatted();
                    }
                }
                """;
        List<String> expected = List.of("com.example.Demo#hi()", "com.example.Demo#version");
        assertTrue(ProposalGuardrail.check("com.example", "Demo", expected,
                new RewriteProposal("src/Demo.java", after, "r")).passed());
    }

    @Test
    @DisplayName("未提供公开成员快照（空列表）：不校验，放行")
    void emptyExpectedMembersSkipsCheck() {
        String after = "package com.example;\n\npublic class Demo {\n}\n";
        assertTrue(ProposalGuardrail.check("com.example", "Demo", List.of(),
                new RewriteProposal("src/Demo.java", after, "r")).passed());
    }
}
