package com.example.unfixable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 遗留的库存台账：按 SKU 维护结存。
 *
 * <p>本工程的重点不在这个类，而在于<b>它所在的构建永远不可能通过</b>：
 * {@code src/test/java} 下的测试引用了已被删除的类。而 RemasterAgent 阶段 1 只重写
 * 「入口文件」这一个源文件，模型无论如何都改不到测试源码，因此：
 *
 * <ul>
 *   <li>ANALYZE 成功（本文档语法合法）</li>
 *   <li>REWRITE 成功（模型能产出合法且通过护栏的新版本）</li>
 *   <li>VERIFY 必然失败（测试编译不过），并且失败信息里出现的是<b>另一个文件</b>的错误</li>
 * </ul>
 *
 * <p>这正好覆盖了系统最该被验证的一面：当失败无法通过重写修复时，护栏是否会在
 * {@code max-rewrite-attempts} 轮之后停下，而不是把预算无限烧在一个不可能的任务上。
 * 一个只会「失败就重试」的循环在演示里看不出问题，在账单上看得出来。
 */
public class LegacyStockLedger {

    private final Map<String, Integer> balances = new HashMap<String, Integer>();

    /** 依次应用一批出入库记录；同一 SKU 多次出现时按顺序累加。 */
    public void apply(List<Movement> movements) {
        for (int i = 0; i < movements.size(); i++) {
            Movement movement = movements.get(i);
            Integer current = balances.get(movement.sku());
            int base = current == null ? 0 : current.intValue();
            balances.put(movement.sku(), Integer.valueOf(base + movement.delta()));
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
