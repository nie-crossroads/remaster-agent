package com.example.authtoken;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 有效期判定 —— 迁移目标文件之一（Date 比较 → Instant / Duration）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>{@code SimpleDateFormat} 解析 ISO 时间 + {@code Date.getTime()} 相减</b>：
 *       毫秒差再除以 1000 得到秒。java.time 里 {@code Duration.between} 一句就清楚。</li>
 * </ul>
 *
 * <p>是否过期的布尔结果是<b>可观测契约</b>，迁移成 {@code Instant/Duration} 后必须一致。
 */
public class ExpiryCheck {

    private static final SimpleDateFormat ISO =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    /** 给定签发时间与当前时间（ISO 字符串）与 TTL（秒），判断是否已过期（严格大于 TTL 才算过期）。 */
    public boolean isExpired(String issuedAt, String now, int ttlSeconds) {
        try {
            Date issued = ISO.parse(issuedAt);
            Date current = ISO.parse(now);
            long elapsedSeconds = (current.getTime() - issued.getTime()) / 1000L;
            return elapsedSeconds > ttlSeconds;
        } catch (ParseException e) {
            throw new IllegalArgumentException("非法的时间格式: " + issuedAt + " / " + now, e);
        }
    }
}
