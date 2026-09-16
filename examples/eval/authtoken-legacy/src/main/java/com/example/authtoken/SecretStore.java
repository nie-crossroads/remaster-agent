package com.example.authtoken;

import java.util.HashMap;
import java.util.Map;

/**
 * 密钥存储 —— 迁移目标文件之一（吞异常「找不到就返回 null」 → Optional）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>查不到就返回 {@code null}</b>，调用方必须自己判空，否则 NPE。
 *       java.time 之外，这里更该返回 {@code Optional<String>}。</li>
 *   <li>取数过程包了一层 try/catch，任何异常都被吞掉返回 null——典型的「静默失败」。</li>
 * </ul>
 *
 * <p>{@code get} 的「有则返回、无则返回 null」语义是<b>可观测契约</b>；迁移成 Optional 后
 * 调用方应改用 {@code isPresent()}/{@code orElse(...)}，但行为结果不变。
 */
public class SecretStore {

    private final Map<String, String> secrets = new HashMap<String, String>();

    /** 存入一个密钥。 */
    public void put(String key, String value) {
        secrets.put(key, value);
    }

    /** 取出密钥；不存在或读取异常时返回 null。 */
    public String get(String key) {
        try {
            return secrets.get(key);
        } catch (Exception e) {
            return null;
        }
    }
}
