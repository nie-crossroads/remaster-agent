package com.remasteragent.tools.db;

import com.remasteragent.tools.config.DotEnv;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.util.Map;

/**
 * 数据库迁移执行器 —— 用 Flyway 把表结构建起来。
 *
 * <p>业务表与向量表（repo / code_chunk）都在同一个 database（remaster）里，
 * 因此只需一个 Flyway 实例、一套 {@code classpath:db/migration} 脚本（V1 业务 + V2 向量）即可。
 * 原来的「向量库在另一个端口」已塌缩为单库（见 docs/ARCHITECTURE.md ADR-11）。
 *
 * <p>Flyway 自带 {@code flyway_schema_history} 记录表，重复执行只会跑没跑过的版本，
 * 因此这个入口是幂等的，可以随时重跑。
 *
 * <p>运行：
 * <pre>
 * mvn.cmd -pl remaster-agent-tools exec:java -Dexec.mainClass=com.remasteragent.tools.db.DbMigrate
 * </pre>
 */
public final class DbMigrate {

    public static final String LOCATIONS = "classpath:db/migration";

    private DbMigrate() {
    }

    public static void main(String[] args) {
        Map<String, String> env = DotEnv.load();
        DatabaseTargets targets = DatabaseTargets.fromEnv(env);

        System.out.println("=".repeat(72));
        System.out.println(" RemasterAgent 数据库迁移");
        System.out.println("=".repeat(72));

        boolean ok = migrate(targets.business());

        System.out.println();
        System.out.println("=".repeat(72));
        if (ok) {
            System.out.println(" 迁移完成");
        } else {
            System.out.println(" 迁移未全部成功，请检查上面的报错");
            System.exit(1);
        }
        System.out.println("=".repeat(72));
    }

    /** 单库迁移（业务表 + 向量表，V1 + V2 同库）。 */
    public static boolean migrate(DatabaseTargets.JdbcTarget target) {
        System.out.println();
        System.out.println("--- 数据库  " + target.describe());
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(target.url(), target.user(), target.password())
                    // 目标库刚由 DatabaseBootstrap 建出来时是空的，允许把已有结构当作基线
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .locations(LOCATIONS)
                    .validateOnMigrate(true)
                    .load();

            MigrateResult result = flyway.migrate();

            if (result.migrationsExecuted == 0) {
                System.out.println(" [跳过] 已是最新，无需迁移");
            } else {
                System.out.println(" [成功] 执行 " + result.migrationsExecuted + " 个迁移:");
                result.migrations.forEach(m ->
                        System.out.println("         V" + m.version + "  " + m.description));
            }
            return true;
        } catch (Exception e) {
            System.out.println(" [失败] " + e.getMessage());
            return false;
        }
    }
}
