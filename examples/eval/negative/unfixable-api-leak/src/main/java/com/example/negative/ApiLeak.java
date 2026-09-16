package com.example.negative;

import java.util.Hashtable;

/**
 * 遗留的密钥索引：把所有键值对塞进一个全局 {@link Hashtable}。
 *
 * <p>这个类本身能编过、能跑。但把它「现代化」成线程安全 + 对外返回不可变视图，
 * 需要把索引逻辑抽到另一个类 {@code com.example.negative.LeakFreeIndex} ——
 * 而 RemasterAgent 一次只改写一个文件（entryFile），没法新建那个类。
 * 于是「正确」的现代化落不了地，任务应当失败。
 */
public class ApiLeak {

    private final Hashtable<String, String> table = new Hashtable<String, String>();

    public void put(String key, String value) {
        table.put(key, value);
    }

    public String get(String key) {
        return table.get(key);
    }
}
