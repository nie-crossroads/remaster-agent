package com.remasteragent.tools.db;

import com.remasteragent.tools.config.DotEnv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * 建库引导 —— 把「数据库本身还不存在」这件事解决掉。
 *
 * <p><b>为什么必须单独做这一步</b>：Flyway 能建 schema、能建表，但它<b>不能建数据库</b>
 * （{@code CREATE DATABASE} 不是它管的范围）。而本机又没有 {@code psql} 客户端，
 * 所以在没有任何数据库可用的情况下，只能靠 JDBC 直连维护库把库建出来。
 *
 * <p>做法：连到实例的 {@code postgres} 维护库 → 查 {@code pg_database} 判断存在性 →
 * 不存在才建。整段逻辑幂等，重复执行没有副作用。
 *
 * <p>注意 {@code CREATE DATABASE} 不能在事务块里执行，所以必须保持 JDBC 的自动提交开启
 * —— 不要在这里开事务。
 *
 * <p>运行：
 * <pre>
 * mvn.cmd -pl remaster-agent-tools exec:java -Dexec.mainClass=com.remasteragent.tools.db.DatabaseBootstrap
 * </pre>
 */
public final class DatabaseBootstrap {

    private DatabaseBootstrap() {
    }

    public static void main(String[] args) {
        Map<String, String> env = DotEnv.load();
        DatabaseTargets targets = DatabaseTargets.fromEnv(env);

        System.out.println("=".repeat(72));
        System.out.println(" RemasterAgent 建库引导（幂等，可重复执行）");
        System.out.println("=".repeat(72));

        boolean businessOk = ensureDatabase(targets.business(), "业务库（含向量表）");

        System.out.println();
        if (businessOk) {
            System.out.println("数据库已就绪，接着跑 DbMigrate 建表。");
        } else {
            System.out.println("数据库未就绪，请先看上面的报错。");
            System.exit(1);
        }
    }

    /**
     * 确保目标库存在。
     *
     * @return 是否已就绪（已存在或刚创建成功）
     */
    private static boolean ensureDatabase(DatabaseTargets.JdbcTarget target, String label) {
        System.out.println();
        System.out.println("--- " + label + "  " + target.describe());

        try (Connection conn = DriverManager.getConnection(
                target.maintenanceUrl(), target.user(), target.password())) {

            boolean exists = databaseExists(conn, target.database());
            if (exists) {
                System.out.println(" [跳过] 数据库已存在");
                return true;
            }

            // CREATE DATABASE 不能参数化（标识符不能当参数绑定），所以只能拼 SQL。
            // 库名来自我们自己的配置而非外部输入，但仍做一次合法性校验，避免意外注入。
            String dbName = target.database();
            if (!dbName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                System.out.println(" [失败] 数据库名不合法: " + dbName);
                return false;
            }

            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE DATABASE " + dbName
                        + " WITH ENCODING 'UTF8' TEMPLATE template0");
                System.out.println(" [成功] 已创建数据库 " + dbName);
                return true;
            }
        } catch (SQLException e) {
            System.out.println(" [失败] " + e.getMessage());
            return false;
        }
    }

    private static boolean databaseExists(Connection conn, String database) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
