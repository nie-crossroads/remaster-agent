package com.example.authtoken;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Base64Codec} 的行为契约 —— 迁移前后必须逐字节互逆。
 *
 * <p>只断言编解码互逆与已知向量；手写查表还是 {@code java.util.Base64} 是实现细节。
 */
class Base64CodecTest {

    @Test
    @DisplayName("已知向量：foobar 的标准 Base64")
    void knownVector() {
        byte[] data = "foobar".getBytes(StandardCharsets.UTF_8);
        assertEquals("Zm9vYmFy", new Base64Codec().encode(data));
        assertEquals("foobar",
                new String(new Base64Codec().decode("Zm9vYmFy"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("encode/decode 对任意字节互逆（含中文与边界长度）")
    void roundTrip() {
        Base64Codec codec = new Base64Codec();
        byte[] original = "Hello, Base64! 你好".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(original, codec.decode(codec.encode(original)));

        // 长度不是 3 的倍数，专门覆盖填充分支
        assertArrayEquals(new byte[]{1}, codec.decode(codec.encode(new byte[]{1})));
        assertArrayEquals(new byte[]{1, 2}, codec.decode(codec.encode(new byte[]{1, 2})));
        assertArrayEquals(new byte[]{1, 2, 3}, codec.decode(codec.encode(new byte[]{1, 2, 3})));
    }
}
