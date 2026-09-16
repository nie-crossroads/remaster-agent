package com.example.authtoken;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SecretStore} 的行为契约 —— 迁移前后必须一致。
 *
 * <p>只断言「有则返回、无则返回 null」；返回 null 还是 Optional 是调用方适配问题。
 */
class SecretStoreTest {

    @Test
    @DisplayName("存入后取出得到原值")
    void putAndGet() {
        SecretStore store = new SecretStore();
        store.put("api.key", "s3cr3t");
        assertEquals("s3cr3t", store.get("api.key"));
    }

    @Test
    @DisplayName("不存在的 key 返回 null")
    void missingReturnsNull() {
        assertNull(new SecretStore().get("nope"));
    }
}
