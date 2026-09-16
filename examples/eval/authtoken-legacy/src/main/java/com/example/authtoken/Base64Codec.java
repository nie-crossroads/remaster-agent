package com.example.authtoken;

/**
 * Base64 编解码 —— 迁移目标文件之一（手写查表 → java.util.Base64）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>自己维护 Base64 字母表并用位运算拼装</b>（而不是 {@code java.util.Base64}）。
 *       老系统里常见 {@code sun.misc.BASE64Encoder}，但那是 JDK 内部 API，
 *       这里用手写实现更贴近「没有现成工具类」的真实遗留场景。</li>
 * </ul>
 *
 * <p>编解码互逆是<b>可观测契约</b>，迁移成 {@code Base64.getEncoder()} 后必须仍然互逆。
 */
public class Base64Codec {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /** 把字节数组编码为标准 Base64（含 '=' 填充）。 */
    public String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = (i + 1 < data.length) ? (data[i + 1] & 0xFF) : 0;
            int b2 = (i + 2 < data.length) ? (data[i + 2] & 0xFF) : 0;
            int triple = (b0 << 16) | (b1 << 8) | b2;
            out.append(ALPHABET.charAt((triple >> 18) & 0x3F));
            out.append(ALPHABET.charAt((triple >> 12) & 0x3F));
            out.append(i + 1 < data.length ? ALPHABET.charAt((triple >> 6) & 0x3F) : '=');
            out.append(i + 2 < data.length ? ALPHABET.charAt(triple & 0x3F) : '=');
        }
        return out.toString();
    }

    /** 把 Base64 字符串解码回字节数组（忽略空白与 '=' 填充）。 */
    public byte[] decode(String encoded) {
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c != '=' && !Character.isWhitespace(c)) {
                clean.append(c);
            }
        }
        String s = clean.toString();
        int groups = (s.length() + 3) / 4; // 不足 4 的部分也算一个分组
        int totalBytes = groups * 3;
        int rem = s.length() % 4;
        if (rem == 3) {
            totalBytes -= 1;
        } else if (rem == 2) {
            totalBytes -= 2;
        }
        byte[] out = new byte[totalBytes];
        int outIdx = 0;
        for (int g = 0; g < s.length(); g += 4) {
            int c0 = ALPHABET.indexOf(s.charAt(g));
            int c1 = ALPHABET.indexOf(s.charAt(g + 1));
            int c2 = (g + 2 < s.length()) ? ALPHABET.indexOf(s.charAt(g + 2)) : 0;
            int c3 = (g + 3 < s.length()) ? ALPHABET.indexOf(s.charAt(g + 3)) : 0;
            int triple = (c0 << 18) | (c1 << 12) | (c2 << 6) | c3;
            if (outIdx < out.length) {
                out[outIdx++] = (byte) ((triple >> 16) & 0xFF);
            }
            if (outIdx < out.length) {
                out[outIdx++] = (byte) ((triple >> 8) & 0xFF);
            }
            if (outIdx < out.length) {
                out[outIdx++] = (byte) (triple & 0xFF);
            }
        }
        return out;
    }
}
