package com.remasteragent.tools.db;

import com.remasteragent.tools.config.DotEnv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * 清空任务数据 —— 让端到端验收可以重复跑。
 *
 * <p><b>为什么需要它。</b>验收要看的是一整条链路：任务状态 → DAG 节点 → 补丁 → 成本。
 * 这些数据会一直累积，跑第二遍时列表里混着上一轮的结果，
 * 「这次的编译通过率是多少」就说不清了 —— 而这种说不清正是评测报告最致命的缺陷。
 * 手工写 SQL 清库既不可复现也容易漏表，所以做成一个可以随时重跑的工具。
 *
 * <p>它只删<b>任务相关</b>的四张表（外加阶段 3 的 {@code human_gate}），
 * 不动表结构、不动 {@code flyway_schema_history}、不动向量表 ——
 * 所以清完之后不需要重新迁移，直接投任务即可。
 *
 * <p>安全设计：必须显式传 {@code --confirm} 才会真的删。
 * 默认执行只打印将要删除的行数并退出 —— 一个连库删数据的工具，
 * 不该因为「手滑回车」就把数据清掉。
 *
 * <p>运行：
 * <pre>
 * mvn.cmd -pl remaster-agent-tools exec:java \
 *   -Dexec.mainClass=com.remasteragent.tools.db.ResetTasks -Dexec.args="--confirm"
 * </pre>
 */
public final class ResetTasks {

    /**
     * 要清空的表，顺序即删除顺序。
     *
     * <p>其实 {@code TRUNCATE ... CASCADE} 会自己处理外键，
     * 但显式列出依赖顺序能让「谁引用了谁」在代码里一眼可见 ——
     * 后面加表时不容易漏。
     */
    private static final List<String> TASK_TABLES = List.of(
            "llm_call", "patch", "human_gate", "dag_node", "migration_task");

    private ResetTasks() {
    }

    public static void main(String[] args) {
        boolean confirmed = List.of(args).contains("--confirm");
        Map<String, String> env = DotEnv.load();
        DatabaseTargets.JdbcTarget target = DatabaseTargets.fromEnv(env).business();

        System.out.println("=".repeat(72));
        System.out.println(" RemasterAgent 任务数据清理" + (confirmed ? "（确认执行）" : "（预演，未删任何数据）"));
        System.out.println("=".repeat(72));
        System.out.println(" 目标库: " + target.describe());

        try (Connection conn = DriverManager.getConnection(
                target.url(), target.user(), target.password())) {

            printCounts(conn, "清理前");

            if (!confirmed) {
                System.out.println();
                System.out.println(" 上面是即将被清空的量。确认无误请在命令末尾加 --confirm 重新执行。");
                return;
            }

            try (Statement st = conn.createStatement()) {
                // 一条 TRUNCATE 覆盖多表：既快，又天然是原子操作 ——
                // 不会出现「任务删了但节点还在」这种半清状态。
                // RESTART IDENTITY 让任务 id 从 1 重新开始，验收报告里的 id 更好读。
                st.executeUpdate("TRUNCATE TABLE " + String.join(", ", TASK_TABLES)
                        + " RESTART IDENTITY CASCADE");
            }
            System.out.println();
            System.out.println(" [成功] 已清空: " + String.join(", ", TASK_TABLES));
        } catch (Exception e) {
            System.out.println(" [失败] " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println();
        System.out.println(" 提示：Redis Stream（remaster:tasks）里可能还有指向已删任务的旧消息。");
        System.out.println("       它们会被 Worker 当作「任务不存在」丢弃并确认，无需手工清理。");
        System.out.println("=".repeat(72));
    }

    private static void printCounts(Connection conn, String label) {
        System.out.println();
        System.out.println(" --- " + label + " ---");
        for (String table : TASK_TABLES) {
            System.out.printf("   %-16s %d 行%n", table, count(conn, table));
        }
    }

    private static long count(Connection conn, String table) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception e) {
            // 表不存在（比如 human_gate 还没建）不该让整个工具失败
            return -1L;
        }
    }
}
