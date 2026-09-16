package com.example.inventory;

/**
 * 出入库流水 —— 手写扩容数组（JDK 1.4 里没有 {@code ArrayList} 的泛型写法时的常见做法）。
 *
 * <p>迁移目标：{@code String[]} + 手工 {@code grow()} → {@code ArrayList<String>}，
 * {@code toArray()} 的手工拷贝 → {@code toArray(new String[0])}，
 * 拼接 → {@link String#join}。
 *
 * <h2>刻意保留的一条性质：初始容量 4，翻倍扩容</h2>
 * <p>单测断言 {@link #capacity()} 在跨过阈值时的取值。这条不是实现细节 ——
 * 它把「扩容是否发生、何时发生」变成可观测行为，迁移成 {@code ArrayList} 之后
 * {@code capacity()} 应当返回内部数组的真实长度（{@code ArrayList} 没有公开它，
 * 所以迁移时要么保留一个容量字段，要么把这个方法一并说明为「不再可观测」）。
 * 把它钉住，是为了让「改了实现但没改语义」与「改了语义」能被区分开。
 */
public class MovementLog {

    private static final int INITIAL_CAPACITY = 4;

    private String[] entries = new String[INITIAL_CAPACITY];
    private int size = 0;

    public void add(String entry) {
        if (entry == null) {
            throw new IllegalArgumentException("流水不能为 null");
        }
        if (size == entries.length) {
            grow();
        }
        entries[size] = entry;
        size++;
    }

    private void grow() {
        String[] bigger = new String[entries.length * 2];
        System.arraycopy(entries, 0, bigger, 0, entries.length);
        entries = bigger;
    }

    public int size() {
        return size;
    }

    /** 底层数组当前长度（含尚未使用的空位）。 */
    public int capacity() {
        return entries.length;
    }

    public String get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("越界: " + index + "，共 " + size + " 条");
        }
        return entries[index];
    }

    /** 防御性拷贝，长度等于 {@link #size()} 而不是容量。 */
    public String[] toArray() {
        String[] copy = new String[size];
        System.arraycopy(entries, 0, copy, 0, size);
        return copy;
    }

    /** 用 {@code separator} 拼成一行 —— 循环内 {@code +=} 是这里的坏味道。 */
    public String join(String separator) {
        String text = "";
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                text = text + separator;
            }
            text = text + entries[i];
        }
        return text;
    }

    /** 含 {@code keyword} 的流水条数（大小写敏感）。 */
    public int countOf(String keyword) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (entries[i].indexOf(keyword) >= 0) {
                count++;
            }
        }
        return count;
    }

    /** 清空流水，但<b>保留</b>已分配的容量 —— 台账一天一清，容量不该跟着缩回去。 */
    public void clear() {
        for (int i = 0; i < size; i++) {
            entries[i] = null;
        }
        size = 0;
    }
}
