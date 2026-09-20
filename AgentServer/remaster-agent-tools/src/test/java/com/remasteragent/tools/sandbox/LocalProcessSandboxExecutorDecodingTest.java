package com.remasteragent.tools.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定沙箱日志解码契约：<b>绝不因为个别非 UTF-8 字节丢掉整份日志</b>。
 *
 * <p>这是端到端验收时暴露出的真实缺陷的回归测试：Windows 上 {@code mvn.cmd} 的宿主
 * 进程会按控制台代码页往日志里追加 GBK 字节，而沙箱原先用
 * {@code Files.readString(..., UTF_8)} 严格解码，结果一个坏字节就让整份日志
 * ——包括 {@code javac} 的编译错误详情——读取失败返回空串。而这份错误详情正是
 * 喂给模型做下一轮重写的关键输入，丢掉它等于让回退重写「闭着眼睛改」。
 */
class LocalProcessSandboxExecutorDecodingTest {

    @Test
    @DisplayName("纯 UTF-8 内容原样解出，含中文与 javac 错误")
    void plainUtf8RoundTrips() {
        String text = "编译失败\nERROR: /src/Foo.java:12: 找不到符号\nBUILD FAILURE";

        String decoded = SandboxIo.decodeLenient(text.getBytes(StandardCharsets.UTF_8));

        assertEquals(text, decoded);
    }

    @Test
    @DisplayName("混入非法 UTF-8 字节时不抛异常，且其余内容完整保留")
    void strayByteDoesNotDestroyLog() {
        byte[] valid = "BUILD FAILURE\n".getBytes(StandardCharsets.UTF_8);
        // 构造真正的非法 UTF-8 序列：0xC3 后面必须跟 0x80~0xBF，这里跟 0x28 使其成为坏字节
        byte[] stray = {(byte) 0xC3, (byte) 0x28};
        byte[] trailing = "ERROR: cannot find symbol\n".getBytes(StandardCharsets.UTF_8);

        byte[] mixed = new byte[valid.length + stray.length + trailing.length];
        System.arraycopy(valid, 0, mixed, 0, valid.length);
        System.arraycopy(stray, 0, mixed, valid.length, stray.length);
        System.arraycopy(trailing, 0, mixed, valid.length + stray.length, trailing.length);

        String decoded = SandboxIo.decodeLenient(mixed);

        // 关键断言：坏字节之前与之后的诊断信息都还在
        assertTrue(decoded.startsWith("BUILD FAILURE"), "坏字节之前的日志不能丢");
        assertTrue(decoded.contains("ERROR: cannot find symbol"), "坏字节之后的日志不能丢");
        // 坏字节被替换成 U+FFFD，而不是让整串变成空
        assertTrue(decoded.contains("\uFFFD"), "非法字节应替换为 U+FFFD");
    }

    @Test
    @DisplayName("真实场景：GBK 控制台字节夹在 UTF-8 编译错误之间")
    void gbkConsoleBytesAreTolerated() {
        // 模拟 cmd.exe 按 GBK 写下的「'mvn' 不是内部或外部命令」之类提示（GBK 双字节）
        byte[] gbkPrefix = {(byte) 0xB2, (byte) 0xE2, (byte) 0xCA, (byte) 0xD4};
        byte[] utf8Error = "Foo.java:7: error: ';' expected".getBytes(StandardCharsets.UTF_8);

        byte[] mixed = new byte[gbkPrefix.length + utf8Error.length];
        System.arraycopy(gbkPrefix, 0, mixed, 0, gbkPrefix.length);
        System.arraycopy(utf8Error, 0, mixed, gbkPrefix.length, utf8Error.length);

        String decoded = SandboxIo.decodeLenient(mixed);

        assertTrue(decoded.contains("Foo.java:7: error: ';' expected"),
                "ASCII 的 javac 错误必须完整保留 —— 它才是模型真正要读的部分");
        assertFalse(decoded.isBlank(), "整份日志不能退化成空串");
    }

    @Test
    @DisplayName("空输入返回空串，不抛异常")
    void emptyInputIsSafe() {
        assertEquals("", SandboxIo.decodeLenient(new byte[0]));
    }

    @Test
    @DisplayName("编码三件套齐全 —— stdout/stderr.encoding 不是冗余参数")
    void jvmOverridesCarryAllThreeEncodingFlags() {
        String mavenOpts = LocalProcessSandboxExecutor.jvmOverrides(1024).get("MAVEN_OPTS");

        // JDK 18 起 file.encoding 不再控制 System.out/err：少了后两个，javac 的中文报错会变乱码。
        // 这条断言是为了防止有人把它们当重复参数删掉。
        assertTrue(mavenOpts.contains("-Dfile.encoding=UTF-8"), "缺 file.encoding");
        assertTrue(mavenOpts.contains("-Dstdout.encoding=UTF-8"), "缺 stdout.encoding（javac 诊断走 System.err 时会变 GBK）");
        assertTrue(mavenOpts.contains("-Dstderr.encoding=UTF-8"), "缺 stderr.encoding（javac 诊断走 System.err 时会变 GBK）");
        assertTrue(mavenOpts.contains("-Xmx1024m"), "内存上限必须透传");
    }

    @Test
    @DisplayName("MAVEN_ARGS 置空，屏蔽外部残留参数")
    void mavenArgsIsCleared() {
        assertEquals("", LocalProcessSandboxExecutor.jvmOverrides(512).get("MAVEN_ARGS"));
    }
}
