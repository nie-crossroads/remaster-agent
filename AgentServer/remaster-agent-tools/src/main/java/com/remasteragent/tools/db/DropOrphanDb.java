package com.remasteragent.tools.db;

import com.remasteragent.tools.config.DotEnv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * 删除孤儿数据库 remaster_vector。
 *
 * <p>单库改造前，向量表曾放在独立的数据库 remaster_vector 上（原计划另一个端口 5433）。
 * 现在向量表（repo / code_chunk）已随 V2 迁移并进业务库 remaster，
 * 这个独立库就成了没人连的孤儿 —— 留着只会让人误以为「还得维护两个库」。
 *
 * <p>本工具只删这一个固定的库，且会先把里面的表列出来给你看一眼，再终止连接后删除。
 * 运行：
 * <pre>
 * mvn.cmd -pl remaster-agent-tools exec:java -Dexec.mainClass=com.remasteragent.tools.db.DropOrphanDb
 * </pre>
 */
public final class DropOrphanDb {

    private static final String ORPHAN = "remaster_vector";

    private DropOrphanDb() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = DotEnv.load();
        String base = "jdbc:postgresql://" + env.get("POSTGRES_HOST") + ":" + env.get("POSTGRES_PORT");
        String user = env.get("POSTGRES_USER");
        String pwd = env.get("POSTGRES_PASSWORD");

        try (Connection c = DriverManager.getConnection(base + "/postgres", user, pwd)) {
            c.setAutoCommit(true);

            boolean exists;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select 1 from pg_database where datname = '" + ORPHAN + "'")) {
                exists = rs.next();
            }
            if (!exists) {
                System.out.println("[跳过] 数据库 " + ORPHAN + " 不存在，无需删除");
                return;
            }

            System.out.println("[信息] " + ORPHAN + " 存在，准备删除。其中的表：");
            try (Connection oc = DriverManager.getConnection(base + "/" + ORPHAN, user, pwd)) {
                try (ResultSet rs = oc.getMetaData().getTables(null, "public", null, new String[]{"TABLE"})) {
                    boolean any = false;
                    while (rs.next()) {
                        System.out.println("   - " + rs.getString("TABLE_NAME"));
                        any = true;
                    }
                    if (!any) {
                        System.out.println("   （public 模式下没有表）");
                    }
                }
            } catch (Exception e) {
                System.out.println("   （无法连入列举表清单: " + e.getMessage() + "）");
            }

            try (Statement st = c.createStatement()) {
                st.execute("select pg_terminate_backend(pid) from pg_stat_activity where datname = '" + ORPHAN + "'");
            }
            System.out.println("[信息] 已终止 " + ORPHAN + " 上的活动连接");

            try (Statement st = c.createStatement()) {
                st.execute("DROP DATABASE " + ORPHAN);
            }
            System.out.println("[成功] 已删除孤儿数据库 " + ORPHAN);
        }
    }
}
