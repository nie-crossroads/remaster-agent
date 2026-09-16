package com.example.negative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnsatisfiableTest {

    @Test
    @DisplayName("翻倍：同一个调用被断言成两个互相矛盾的结果")
    void contradictoryAssertions() {
        Unsatisfiable u = new Unsatisfiable();
        assertEquals(6, u.doubleIt(3));   // 3*2 = 6，与源码一致
        assertEquals(7, u.doubleIt(3));   // 同一调用又期望 7 —— 矛盾，任何实现都不可能同时通过
    }
}
