package com.remasteragent.tools.db;

import com.remasteragent.tools.config.DotEnv;
import org.flywaydb.core.Flyway;

import java.util.Map;

/**
 * 修复已应用迁移的校验和（checksum）。
 *
 * <p>当某个已执行过的迁移脚本被<b>合法</b>地改动过（比如补注释、调建表顺序），
 * 它算出来的校验和会和 {@code flyway_schema_history} 里记录的旧值不一致，
 * 导致 {@code validate-on-migrate} 在每次启动时报 {@code checksum mismatch} 而中止。
 *
 * <p>这种情况不是「脚本有问题」，而是「历史记录没跟上脚本」——
 * 用 {@link Flyway#repair()} 把历史记录里的校验和刷新成当前文件的值即可，
 * 不会改动任何表结构或数据。
 *
 * <p>运行：
 * <pre>
 * mvn.cmd -pl remaster-agent-tools exec:java -Dexec.mainClass=com.remasteragent.tools.db.DbRepair
 * </pre>
 */
public final class DbRepair {

    private DbRepair() {
    }

    public static void main(String[] args) {
        Map<String, String> env = DotEnv.load();
        DatabaseTargets targets = DatabaseTargets.fromEnv(env);

        System.out.println("=".repeat(72));
        System.out.println(" RemasterAgent 迁移校验和修复");
        System.out.println("=".repeat(72));
        System.out.println(" 目标: " + targets.business().describe());
        System.out.println(" 作用: 把 flyway_schema_history 里的校验和刷新为当前脚本的值");
        System.out.println("       （不改动任何表结构 / 数据）");
        System.out.println();

        Flyway flyway = Flyway.configure()
                .dataSource(targets.business().url(), targets.business().user(), targets.business().password())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations(DbMigrate.LOCATIONS)
                .load();

        flyway.repair();

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println(" 修复完成，可重新运行 DbMigrate 继续未完成的迁移");
        System.out.println("=".repeat(72));
    }
}
