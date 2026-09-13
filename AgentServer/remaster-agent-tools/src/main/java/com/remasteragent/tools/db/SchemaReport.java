package com.remasteragent.tools.db;

import com.remasteragent.tools.config.DotEnv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

/**
 * 表结构报告 —— 用来证明「表建对了、字段注释齐了」，而不是靠感觉。
 *
 * <p>它会逐表列出字段、类型、可空性与<b>中文注释</b>，并单独统计有多少字段缺注释。
 * 「字段必须有注释」是本项目的硬性约定，靠人工检查一定会漏，
 * 所以做成一个可以随时重跑的检查入口。
 *
 * <p>运行：
 * <pre>
 * mvn.cmd -pl remaster-agent-tools exec:java -Dexec.mainClass=com.remasteragent.tools.db.SchemaReport
 * </pre>
 */
public final class SchemaReport {

    private SchemaReport() {
    }

    public static void main(String[] args) {
        Map<String, String> env = DotEnv.load();
        DatabaseTargets targets = DatabaseTargets.fromEnv(env);

        int totalMissing = 0;
        // 业务库与向量表（repo / code_chunk）同库，一份报告即可
        totalMissing += report("业务库（含向量表）", targets.business());

        System.out.println();
        System.out.println("=".repeat(72));
        if (totalMissing == 0) {
            System.out.println(" 全部字段都有注释");
        } else {
            System.out.println(" 有 " + totalMissing + " 个字段缺少注释，需要补 COMMENT ON COLUMN");
        }
        System.out.println("=".repeat(72));

        System.exit(totalMissing == 0 ? 0 : 1);
    }

    private static int report(String label, DatabaseTargets.JdbcTarget target) {
        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println(" " + label + "  " + target.describe());
        System.out.println("=".repeat(72));

        String sql = """
                SELECT c.table_name,
                       c.column_name,
                       c.data_type,
                       c.is_nullable,
                       COALESCE(pgd.description, '') AS comment
                  FROM information_schema.columns c
                  LEFT JOIN pg_catalog.pg_statio_all_tables st
                         ON st.schemaname = c.table_schema AND st.relname = c.table_name
                  LEFT JOIN pg_catalog.pg_description pgd
                         ON pgd.objoid = st.relid AND pgd.objsubid = c.ordinal_position
                 WHERE c.table_schema = 'public'
                   AND c.table_name NOT LIKE 'flyway%'
                 ORDER BY c.table_name, c.ordinal_position
                """;

        int missing = 0;
        String currentTable = null;
        try (Connection conn = DriverManager.getConnection(target.url(), target.user(), target.password());
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String table = rs.getString("table_name");
                if (!table.equals(currentTable)) {
                    currentTable = table;
                    System.out.println();
                    System.out.println("  [" + table + "]");
                }
                String comment = rs.getString("comment");
                boolean blank = comment == null || comment.isBlank();
                if (blank) {
                    missing++;
                }
                System.out.printf("    %-20s %-18s %-4s %s%n",
                        rs.getString("column_name"),
                        rs.getString("data_type"),
                        "YES".equals(rs.getString("is_nullable")) ? "null" : "NN",
                        blank ? "<<缺注释>>" : comment);
            }

            if (currentTable == null) {
                System.out.println("  (没有任何表 —— 先跑 DbMigrate)");
            }
        } catch (Exception e) {
            System.out.println("  [失败] " + e.getMessage());
            return -1;
        }
        return missing;
    }
}
