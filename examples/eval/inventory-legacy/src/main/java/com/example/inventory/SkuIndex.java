package com.example.inventory;

import java.util.Arrays;

/**
 * sku 索引 —— 手写二分查找与 JDK 5 之前的数组用法。
 *
 * <p>迁移目标：手写二分 → {@link Arrays#binarySearch}，{@code new String[n]} +
 * {@link System#arraycopy} 的扩容 → 集合或 {@link Arrays#copyOf}，
 * 排序后的数组 → 显式泛型与 {@code List<String>}。
 *
 * <h2>两条被单测钉住的语义</h2>
 * <ol>
 *   <li><b>查不到返回 -1，不是「插入点」。</b>{@code Arrays.binarySearch} 返回
 *       {@code -(insertionPoint) - 1}，直接照搬会让 {@code indexOf} 在缺货时返回负数 ——
 *       迁移时最容易顺手引入的回归，{@code missingSkuReturnsMinusOne} 专门盯它。</li>
 *   <li><b>构造时排序，且去重。</b>输入顺序不影响任何查询结果；
 *       {@code range} 的边界是闭区间，返回的结果按字典序。</li>
 * </ol>
 */
public class SkuIndex {

    private final String[] skus;

    /** 按字典序去重排序后建索引。忽略 {@code null} 与空串。 */
    public SkuIndex(String... raw) {
        String[] cleaned = new String[raw.length];
        int size = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] != null && raw[i].length() > 0) {
                cleaned[size++] = raw[i];
            }
        }
        String[] trimmed = new String[size];
        System.arraycopy(cleaned, 0, trimmed, 0, size);
        Arrays.sort(trimmed);

        // 去重（排序后相同元素相邻）
        int unique = 0;
        for (int i = 0; i < trimmed.length; i++) {
            if (i == 0 || !trimmed[i].equals(trimmed[i - 1])) {
                trimmed[unique++] = trimmed[i];
            }
        }
        this.skus = new String[unique];
        System.arraycopy(trimmed, 0, this.skus, 0, unique);
    }

    /** 索引号；不存在返回 -1。 */
    public int indexOf(String sku) {
        if (sku == null) {
            return -1;
        }
        int low = 0;
        int high = skus.length - 1;
        while (low <= high) {
            int mid = (low + high) / 2;
            int cmp = skus[mid].compareTo(sku);
            if (cmp == 0) {
                return mid;
            } else if (cmp < 0) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return -1;
    }

    public boolean contains(String sku) {
        return indexOf(sku) >= 0;
    }

    public int size() {
        return skus.length;
    }

    /** 第 {@code i} 个 sku（按字典序）。 */
    public String at(int i) {
        if (i < 0 || i >= skus.length) {
            throw new IndexOutOfBoundsException("索引越界: " + i + "，共 " + skus.length + " 个");
        }
        return skus[i];
    }

    /**
     * 闭区间 {@code [from, to]} 内的 sku，按字典序。
     *
     * <p>{@code from} 与 {@code to} 本身可以不在索引里 —— 这是字典序区间，不是下标区间。
     */
    public String[] range(String from, String to) {
        String[] buffer = new String[skus.length];
        int size = 0;
        for (int i = 0; i < skus.length; i++) {
            if (skus[i].compareTo(from) >= 0 && skus[i].compareTo(to) <= 0) {
                buffer[size++] = skus[i];
            }
        }
        String[] result = new String[size];
        System.arraycopy(buffer, 0, result, 0, size);
        return result;
    }

    /** 全部 sku 的防御性拷贝。 */
    public String[] toArray() {
        String[] copy = new String[skus.length];
        System.arraycopy(skus, 0, copy, 0, skus.length);
        return copy;
    }
}
