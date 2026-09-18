package com.remasteragent.tools.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内容哈希 —— 补丁的「基线指纹」与回写结果的核对依据。
 *
 * <p>为什么值得单独抽出来：{@code patch.original_hash}（改写前的指纹）与
 * {@code source_write_back.files[].sha256}（写回后的指纹）必须是<b>同一套算法</b>，
 * 否则回写预检里那句「基线有没有被人动过」的比对就是拿两种指纹在比 —— 永远不相等，
 * 而失败原因会显示成「源文件已被修改」，把人引向完全错误的排查方向。
 */
public final class ContentHash {

    private ContentHash() {
    }

    /** UTF-8 编码后的 SHA-256，十六进制小写。 */
    public static String sha256(String content) {
        return sha256(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256，十六进制小写。 */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制要求的算法，取不到说明 JVM 环境已经不可信 —— 直接失败，
            // 而不是返回 null 让上层把「算不出指纹」当成「指纹为空所以校验通过」
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}
