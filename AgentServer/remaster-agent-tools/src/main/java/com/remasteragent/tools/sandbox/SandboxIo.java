package com.remasteragent.tools.sandbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 两个沙箱执行器共用的日志读写：宽容解码 + 头尾截断。
 *
 * <p>抽出来是因为 local 与 docker 两种实现拿回日志的方式完全一致（都落文件、都截断、
 * 都宽容解码），放两份迟早会分叉——尤其「宽容解码」这条契约是用一次真实事故换来的，
 * 必须只在一个地方被单测锁死。
 */
final class SandboxIo {

    private static final Logger log = LoggerFactory.getLogger(SandboxIo.class);

    private SandboxIo() {
    }

    /**
     * 读取日志并截断。
     *
     * <p>截断策略是<b>头尾都留</b>：Maven 把编译错误和失败测试堆栈打在中间偏前，
     * 最后的 BUILD FAILURE 汇总在尾部。只留尾部会丢错误详情，只留头部会丢结论。
     */
    static String readTruncated(Path logFile, int maxOutputChars) {
        try {
            if (!Files.exists(logFile)) {
                return "";
            }
            String content = decodeLenient(Files.readAllBytes(logFile));
            if (content.length() <= maxOutputChars) {
                return content;
            }
            int headLen = maxOutputChars / 3;
            int tailLen = maxOutputChars - headLen;
            return content.substring(0, headLen)
                    + "\n\n...[日志过长，中间省略 " + (content.length() - maxOutputChars) + " 字符]...\n\n"
                    + content.substring(content.length() - tailLen);
        } catch (IOException e) {
            log.warn("读取沙箱日志失败: {}", logFile, e);
            return "";
        }
    }

    /**
     * 宽容解码：非 UTF-8 字节替换为 U+FFFD，而不是抛 {@link java.nio.charset.MalformedInputException}。
     *
     * <p><b>为什么必须宽容</b>：我们给 Maven 的 JVM 设了 {@code -Dfile.encoding=UTF-8}，
     * 但 Windows 上 {@code mvn.cmd} 的宿主进程（{@code cmd.exe}）会按控制台代码页
     * （中文系统通常是 GBK/CP936）往<b>同一份</b>日志文件里追加字节。若用
     * {@code Files.readString(..., UTF_8)} 严格解码，只要有<b>一个</b>坏字节，
     * 整份日志读取就会失败并返回空串 —— 而这份日志恰恰装着编译错误详情，
     * 是喂给模型做下一轮重写的最有价值输入。为了不让一个字符丢掉全部诊断信息，
     * 这里宁可留下替换符。
     *
     * <p>被替换掉的字符只会影响 Maven 自身的本地化输出（例如中文构建提示），
     * 而不会影响 {@code javac} 的编译错误 —— 后者含类名/行号，是 ASCII 的。
     */
    static String decodeLenient(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // 理论上 REPLACE 策略下不会走到这里，留作最后兜底：绝不因为编码问题丢日志
            log.warn("日志解码异常，退化为按 UTF-8 直接构造字符串", e);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
