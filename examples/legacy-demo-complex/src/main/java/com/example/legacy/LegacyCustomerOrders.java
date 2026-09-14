package com.example.legacy;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 遗留的客户订单服务 —— RemasterAgent 的「复杂版」迁移目标。
 *
 * <p>相比 legacy-demo 的单一报表类，这里故意把更多 JDK 8 时代写法与更多业务分支揉进一个类，
 * 让「现代化」更有挑战，也更能压测阶段 1 的闭环：
 * <ul>
 *   <li>嵌套可变数据结构 {@code Order}（包可见字段，模型可自由改成记录或不可变）</li>
 *   <li>{@link Date} + {@link Calendar} + {@link SimpleDateFormat} 时间处理</li>
 *   <li>匿名 {@link Comparator}、手工 {@code ArrayList<Type>} 泛型、显式装箱 / 拆箱</li>
 *   <li>遗留 {@code null} 语义（无订单时 {@code oldestOrderId()} 返回 null）</li>
 *   <li>魔法数字折扣规则、{@link HashMap} 手工计数</li>
 *   <li>多行文本用 {@code StringBuilder} 拼接（text block 的改造靶子）</li>
 * </ul>
 *
 * <p>所有这些写法在 JDK 21 上<b>都能编译</b>——「遗留」是写法脱节，不是编译不过。
 * 验收依据是行为契约（见测试），不是能不能编过。公开方法签名与输出文本是<b>可观测契约</b>，
 * 迁移后必须逐字保持一致；{@code Order} 是内部实现细节，模型可自由重写。
 */
public class LegacyCustomerOrders {

    private static final TimeZone REPORT_ZONE = TimeZone.getTimeZone("UTC");
    private static final String TS_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 生成时刻，构造时固定。所有订单的 placedAt 也钉在这一刻，保证时间断言可确定。 */
    private final Date generatedAt;

    private final List<Order> orders = new ArrayList<Order>();

    public LegacyCustomerOrders() {
        this(System.currentTimeMillis());
    }

    public LegacyCustomerOrders(long generatedAtEpochMillis) {
        this.generatedAt = new Date(generatedAtEpochMillis);
    }

    /**
     * 新增一笔订单。status 取 "NEW"/"SHIPPED"/"CANCELLED"，非法或 null 按 NEW 处理；
     * id 为空或 null 的订单被静默忽略（遗留的「容错」写法）。
     */
    public void addOrder(String id, double unitPrice, int qty, String status) {
        if (id == null || id.isEmpty()) {
            return;
        }
        String safeStatus = status;
        if (safeStatus == null) {
            safeStatus = "NEW";
        }
        if (!(safeStatus.equals("NEW") || safeStatus.equals("SHIPPED") || safeStatus.equals("CANCELLED"))) {
            safeStatus = "NEW";
        }
        Order order = new Order();
        order.id = id;
        order.unitPrice = Double.valueOf(unitPrice);
        order.qty = qty;
        order.status = safeStatus;
        order.placedAt = new Date(generatedAt.getTime());
        orders.add(order);
    }

    public int orderCount() {
        return orders.size();
    }

    /** 营收 = 所有非 CANCELLED 订单 unitPrice * qty 之和。 */
    public double totalRevenue() {
        double sum = 0d;
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            if (!o.status.equals("CANCELLED")) {
                sum += o.unitPrice.doubleValue() * o.qty;
            }
        }
        return sum;
    }

    /** 平均客单价 = 非取消订单营收 / 非取消订单数；无有效订单返回 0。 */
    public double averageOrderValue() {
        double revenue = 0d;
        int count = 0;
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            if (!o.status.equals("CANCELLED")) {
                revenue += o.unitPrice.doubleValue() * o.qty;
                count++;
            }
        }
        if (count == 0) {
            return 0d;
        }
        return revenue / count;
    }

    /** 已发货订单 id，按字典序升序（匿名 Comparator 的典型场景）。 */
    public List<String> shippedOrderIds() {
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            if (o.status.equals("SHIPPED")) {
                ids.add(o.id);
            }
        }
        Collections.sort(ids, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return left.compareTo(right);
            }
        });
        return ids;
    }

    /** 最早下单的订单 id；无订单返回 null（遗留的 null 语义，模型不得改成抛异常）。 */
    public String oldestOrderId() {
        if (orders.isEmpty()) {
            return null;
        }
        Order oldest = orders.get(0);
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            if (o.placedAt.before(oldest.placedAt)) {
                oldest = o;
            }
        }
        return oldest.id;
    }

    /** 是否存在某自然年下的订单（按 placedAt 的年份判断）。 */
    public boolean hasOrdersPlacedIn(int year) {
        Calendar calendar = Calendar.getInstance(REPORT_ZONE);
        for (int i = 0; i < orders.size(); i++) {
            calendar.setTime(orders.get(i).placedAt);
            if (calendar.get(Calendar.YEAR) == year) {
                return true;
            }
        }
        return false;
    }

    /** 各状态订单数（HashMap 手工计数，遗留写法）。 */
    public Map<String, Integer> statusCounts() {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            Integer current = counts.get(o.status);
            if (current == null) {
                current = Integer.valueOf(0);
            }
            counts.put(o.status, Integer.valueOf(current.intValue() + 1));
        }
        return counts;
    }

    /** 营收超过 100 时整体打 9 折（遗留的魔法数字规则）。 */
    public double discountedRevenue() {
        double revenue = totalRevenue();
        if (revenue > 100d) {
            return revenue * 0.9d;
        }
        return revenue;
    }

    /** UTC 时间戳文本。 */
    public String timestampUtc() {
        return newFormatter(TS_PATTERN).format(generatedAt);
    }

    /** 多行汇总（StringBuilder 拼接，text block 改造靶子）。 */
    public String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append("Customer Orders Summary\n");
        builder.append("Generated: ").append(timestampUtc()).append(" (UTC)\n");
        builder.append("Orders: ").append(orderCount()).append("\n");
        builder.append("Revenue: ").append(formatMoney(totalRevenue())).append("\n");
        return builder.toString();
    }

    /** 完整报表 = 汇总 + 已发货清单。 */
    public String report() {
        StringBuilder builder = new StringBuilder();
        builder.append(summary());
        builder.append("----\n");
        builder.append("Shipped:\n");
        List<String> shipped = shippedOrderIds();
        for (int i = 0; i < shipped.size(); i++) {
            builder.append("  ").append(shipped.get(i)).append("\n");
        }
        return builder.toString();
    }

    private static String formatMoney(double amount) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }

    private static SimpleDateFormat newFormatter(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        format.setTimeZone(REPORT_ZONE);
        return format;
    }

    /**
     * 遗留的内部数据结构：可变、包可见字段。
     * 这是实现细节，迁移时可改为不可变记录或私有字段，只要 {@link LegacyCustomerOrders} 的行为不变即可。
     */
    static class Order {
        String id;
        Double unitPrice;
        int qty;
        String status;
        Date placedAt;
    }
}
