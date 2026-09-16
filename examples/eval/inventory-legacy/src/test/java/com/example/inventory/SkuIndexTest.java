package com.example.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkuIndex} 的行为契约。
 *
 * <p>最关键的两条：查不到必须返回 -1（不能被 {@code Arrays.binarySearch} 的
 * 插入点编码带偏），以及构造时的排序去重让结果与输入顺序无关。
 */
class SkuIndexTest {

    private static SkuIndex sample() {
        return new SkuIndex("SKU-C", "SKU-A", "SKU-B", "SKU-A", "", null);
    }

    @Test
    @DisplayName("构造时排序去重，忽略 null 与空串")
    void dedupAndSortOnConstruction() {
        SkuIndex index = sample();
        assertEquals(3, index.size());
        assertArrayEquals(new String[]{"SKU-A", "SKU-B", "SKU-C"}, index.toArray());
    }

    @Test
    @DisplayName("查得到返回下标，查不到返回 -1（不是插入点编码）")
    void indexOfFoundAndMissing() {
        SkuIndex index = sample();
        assertEquals(0, index.indexOf("SKU-A"));
        assertEquals(1, index.indexOf("SKU-B"));
        assertEquals(2, index.indexOf("SKU-C"));

        assertEquals(-1, index.indexOf("SKU-AAA"), "比最小的还小，binarySearch 会返回 -1 —— 恰好与约定一致");
        assertEquals(-1, index.indexOf("SKU-D"), "落在末尾之外的插入点编码是 -4，必须换算成 -1");
        assertEquals(-1, index.indexOf("SKU-A0"), "落在中间的插入点编码是 -2，同样必须换算成 -1");
        assertEquals(-1, index.indexOf(null));
    }

    @Test
    @DisplayName("contains 与 indexOf 一致")
    void containsMatchesIndexOf() {
        SkuIndex index = sample();
        assertTrue(index.contains("SKU-B"));
        assertFalse(index.contains("SKU-Z"));
    }

    @Test
    @DisplayName("按字典序取第 i 个，越界抛 IndexOutOfBoundsException")
    void atAndBounds() {
        SkuIndex index = sample();
        assertEquals("SKU-A", index.at(0));
        assertEquals("SKU-C", index.at(2));
        assertThrows(IndexOutOfBoundsException.class, () -> index.at(3));
        assertThrows(IndexOutOfBoundsException.class, () -> index.at(-1));
    }

    @Test
    @DisplayName("range 是字典序闭区间，端点本身可以不在索引里")
    void rangeIsLexicographicClosedInterval() {
        SkuIndex index = new SkuIndex("SKU-A", "SKU-B", "SKU-C", "SKU-D");

        assertArrayEquals(new String[]{"SKU-B", "SKU-C"}, index.range("SKU-B", "SKU-C"));
        assertArrayEquals(new String[]{"SKU-B", "SKU-C", "SKU-D"}, index.range("SKU-A0", "SKU-Z"));
        assertArrayEquals(new String[]{}, index.range("SKU-E", "SKU-Z"));
    }

    @Test
    @DisplayName("toArray 是拷贝，改它不影响索引")
    void toArrayIsDefensiveCopy() {
        SkuIndex index = sample();
        String[] copy = index.toArray();
        copy[0] = "TAMPERED";
        assertEquals("SKU-A", index.at(0));
    }

    @Test
    @DisplayName("空索引不炸")
    void emptyIndex() {
        SkuIndex index = new SkuIndex();
        assertEquals(0, index.size());
        assertEquals(-1, index.indexOf("SKU-A"));
        assertArrayEquals(new String[]{}, index.range("A", "Z"));
    }
}
