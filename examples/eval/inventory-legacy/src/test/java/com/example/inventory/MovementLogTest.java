package com.example.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MovementLog} 的行为契约。
 *
 * <p>{@code capacity} 的那几条断言不是在实现细节上较真：扩容是这个类存在的理由，
 * 一旦换成 {@code ArrayList}，容量就不再是「可观测行为」。把它先钉住，
 * 迁移时才会被迫显式回答「这个性质还要不要」，而不是悄悄消失。
 */
class MovementLogTest {

    @Test
    @DisplayName("初始容量 4，跨过 4 条后翻倍到 8")
    void growsByDoubling() {
        MovementLog log = new MovementLog();
        assertEquals(4, log.capacity());

        log.add("a");
        log.add("b");
        log.add("c");
        log.add("d");
        assertEquals(4, log.capacity(), "装满但没超，不扩容");

        log.add("e");
        assertEquals(8, log.capacity(), "第 5 条触发翻倍");
        assertEquals(5, log.size());
    }

    @Test
    @DisplayName("toArray 长度等于 size，不是容量")
    void toArrayTrimsToSize() {
        MovementLog log = new MovementLog();
        log.add("a");
        log.add("b");

        assertEquals(4, log.capacity());
        assertArrayEquals(new String[]{"a", "b"}, log.toArray());
        assertEquals(2, log.toArray().length);
    }

    @Test
    @DisplayName("join 用给定分隔符，空流水返回空串")
    void joinWithSeparator() {
        MovementLog log = new MovementLog();
        assertEquals("", log.join(","), "空流水拼出来是空串，不是 null");

        log.add("in-1");
        log.add("out-2");
        log.add("in-3");
        assertEquals("in-1,out-2,in-3", log.join(","));
        assertEquals("in-1 | out-2 | in-3", log.join(" | "));
    }

    @Test
    @DisplayName("countOf 大小写敏感地数关键字")
    void countOfIsCaseSensitive() {
        MovementLog log = new MovementLog();
        log.add("RECEIVE SKU-A");
        log.add("issue SKU-A");
        log.add("RECEIVE SKU-B");

        assertEquals(2, log.countOf("SKU-A"));
        assertEquals(0, log.countOf("sku-a"), "大小写敏感");
    }

    @Test
    @DisplayName("clear 只清条数，保留已分配容量")
    void clearKeepsCapacity() {
        MovementLog log = new MovementLog();
        log.add("a");
        log.add("b");
        log.add("c");
        log.add("d");
        log.add("e");
        assertEquals(8, log.capacity());

        log.clear();
        assertEquals(0, log.size());
        assertEquals(8, log.capacity(), "清流水不该把容量缩回去");
        assertEquals("", log.join(","));
    }

    @Test
    @DisplayName("get 越界抛；null 流水被拒")
    void boundsAndNull() {
        MovementLog log = new MovementLog();
        assertThrows(IndexOutOfBoundsException.class, () -> log.get(0));
        assertThrows(IllegalArgumentException.class, () -> log.add(null));

        log.add("a");
        assertEquals("a", log.get(0));
        assertThrows(IndexOutOfBoundsException.class, () -> log.get(1));
    }
}
