package com.example.authtoken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 令牌摘要 —— 迁移目标文件之一（手工 hex 编码 → HexFormat）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>自己写 hex 转换</b>：逐字节用 {@code String.format("%02x", b)} 拼。
 *       JDK 17+ 有 {@code HexFormat} 一行搞定，但老代码里到处是这种手拼循环。</li>
 * </ul>
 *
 * <p>返回的小写 hex 串是<b>可观测契约</b>，迁移成 {@code HexFormat} 后必须逐字符相同。
 */
public class TokenDigest {

    /** 返回输入字符串 UTF-8 编码后的 SHA-256 摘要，小写 hex 表示。 */
    public String hexDigest(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
