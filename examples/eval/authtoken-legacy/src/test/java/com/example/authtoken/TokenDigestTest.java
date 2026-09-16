package com.example.authtoken;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TokenDigest} 的行为契约 —— 迁移前后必须逐字符相同。
 *
 * <p>只断言已知输入的 SHA-256 hex；手拼 hex 还是 {@code HexFormat} 是实现细节。
 */
class TokenDigestTest {

    @Test
    @DisplayName("abc 的 SHA-256（公开测试向量）")
    void knownVectorAbc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                new TokenDigest().hexDigest("abc"));
    }

    @Test
    @DisplayName("空串的 SHA-256")
    void emptyString() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                new TokenDigest().hexDigest(""));
    }
}
