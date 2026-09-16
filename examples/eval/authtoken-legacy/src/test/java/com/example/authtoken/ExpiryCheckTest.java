package com.example.authtoken;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExpiryCheck} 的行为契约 —— 迁移前后必须一致。
 *
 * <p>只断言过期判定的布尔结果；Date 还是 Instant/Duration 是实现细节。
 */
class ExpiryCheckTest {

    @Test
    @DisplayName("未超过 TTL 不视为过期")
    void withinTtl() {
        assertFalse(new ExpiryCheck().isExpired(
                "2024-01-01T00:00:00", "2024-01-01T00:00:30", 60));
    }

    @Test
    @DisplayName("超过 TTL 视为过期")
    void beyondTtl() {
        assertTrue(new ExpiryCheck().isExpired(
                "2024-01-01T00:00:00", "2024-01-01T00:00:30", 10));
    }

    @Test
    @DisplayName("恰好等于 TTL 不算过期（严格大于才过期）")
    void exactlyAtTtl() {
        assertFalse(new ExpiryCheck().isExpired(
                "2024-01-01T00:00:00", "2024-01-01T00:00:10", 10));
    }
}
