package com.example.unfixable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 遗留的库存台账：按 SKU 维护结存。
 *
 * <p>本工程的重点不在这个类，而在于<b>它所在的构建永远不可能通过</b>：
 * {@code src/test/java} 下的 {@code LegacyStockLedgerTest} 引用了 2023 年重构时删掉的
 * {@code com.example.oracle.SkuFixtures}，而规划与改写都只作用于主源文件，
 * 模型无论如何都改不到测试源码。于是：
 *
 * <ul>
 *   <li>ANALYZE 成功（本文档语法合法）</li>
 *   <li>REWRITE 成功（三个主源文件 {@link LegacyStockLedger} / {@link LegacyStockReport} /
 *       {@link LegacyStockCsvParser} 各产出一份合法且通过护栏的新版本）</li>
 *   <li>VERIFY 必然失败（test-compile 不过），且失败信息里出现的是<b>另一个文件</b>的错误</li>
 * </ul>
 *
 * <p>这正好覆盖了系统最该被验证的一面：当失败无法通过重写修复时，护栏是否会在
 * {@code max-rewrite-attempts} 轮之后停下，而不是把预算无限烧在一个不可能的任务上。
 * 一个只会「失败就重试」的循环在演示里看不出问题，在账单上看得出来。
 */
public class LegacyStockLedger {

    private static final TimeZone REPORT_ZONE = TimeZone.getTimeZone("UTC");
    private static final String TS_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final Map<String, Integer> balances = new HashMap<String, Integer>();

    /** 每个 SKU 最后一次出入库的时刻（JDK 8 时代用 {@link Date} 存时间）。 */
    private final Map<String, Date> lastMovementAt = new HashMap<String, Date>();

    /** 依次应用一批出入库记录；同一 SKU 多次出现时按顺序累加。 */
    public void apply(List<Movement> movements) {
        apply(movements, System.currentTimeMillis());
    }

    /**
     * 应用一批在指定时刻发生的出入库记录。
     *
     * <p>收 {@code long} 而不是 {@link Date}：调用方的 API 边界由调用方决定，
     * 本类内部怎么表示时间是实现细节 —— 换成 {@code java.time} 时不会外溢。
     */
    public void apply(List<Movement> movements, long recordedAtEpochMillis) {
        Date recordedAt = new Date(recordedAtEpochMillis);
        for (int i = 0; i < movements.size(); i++) {
            Movement movement = movements.get(i);
            Integer current = balances.get(movement.sku());
            int base = current == null ? 0 : current.intValue();
            balances.put(movement.sku(), Integer.valueOf(base + movement.delta()));
            lastMovementAt.put(movement.sku(), recordedAt);
        }
    }

    /** 结存；从未出现过的 SKU 视为 0。 */
    public int balance(String sku) {
        if (sku == null) {
            return 0;
        }
        Integer value = balances.get(sku);
        return value == null ? 0 : value.intValue();
    }

    /** 已登记的 SKU，按字典序。 */
    public List<String> skus() {
        List<String> keys = new ArrayList<String>(balances.keySet());
        Collections.sort(keys);
        return keys;
    }

    /** 最后一次出入库的时刻（UTC），形如 {@code 2023-11-14 22:13:20}；没记录过返回空串。 */
    public String lastMovementAtUtc(String sku) {
        Date recordedAt = lastMovementAt.get(sku);
        if (recordedAt == null) {
            return "";
        }
        SimpleDateFormat format = new SimpleDateFormat(TS_PATTERN, Locale.ROOT);
        format.setTimeZone(REPORT_ZONE);
        return format.format(recordedAt);
    }

    /**
     * 某 SKU 是否在给定自然月内发生过出入库。
     *
     * @param month 从 1 开始，不是 {@link Calendar#MONTH} 那种从 0 开始的偏移量
     */
    public boolean movedInMonth(String sku, int year, int month) {
        Date recordedAt = lastMovementAt.get(sku);
        if (recordedAt == null) {
            return false;
        }
        Calendar calendar = Calendar.getInstance(REPORT_ZONE);
        calendar.setTime(recordedAt);
        return calendar.get(Calendar.YEAR) == year
                && calendar.get(Calendar.MONTH) + 1 == month;
    }

    /** 一笔出入库记录：正数入库，负数出库。 */
    public static class Movement {

        private final String sku;
        private final int delta;

        public Movement(String sku, int delta) {
            this.sku = sku;
            this.delta = delta;
        }

        public String sku() {
            return sku;
        }

        public int delta() {
            return delta;
        }
    }
}
