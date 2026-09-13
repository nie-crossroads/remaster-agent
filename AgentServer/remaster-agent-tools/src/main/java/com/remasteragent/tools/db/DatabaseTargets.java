package com.remasteragent.tools.db;

import com.remasteragent.tools.config.DotEnv;

import java.util.Map;

/**
 * 数据库连接信息。
 *
 * <p>项目只用<b>一个</b> PostgreSQL 数据库（remaster）：业务表与向量表（repo / code_chunk）
 * 同库共存。这样 {@code code_chunk.repo_id} 可以建真正的外键，跨库一致性问题不复存在。
 * 若日后向量负载需要独立实例，再把向量表拆到独立库并恢复应用层一致性即可 ——
 * 那是部署期选择，不影响本类的「单目标」抽象。
 *
 * @param business 业务库：状态树 / checkpoint / 补丁 / 成本明细 / 代码分块
 */
public record DatabaseTargets(JdbcTarget business) {

    /**
     * 单个 JDBC 目标。
     *
     * @param host     主机
     * @param port     端口
     * @param database 数据库名
     * @param user     账号
     * @param password 口令
     */
    public record JdbcTarget(String host, int port, String database, String user, String password) {

        /** 目标库的连接串。 */
        public String url() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }

        /**
         * 维护库（postgres）的连接串。
         * 建库时必须连它 —— 目标库还不存在的时候，当然没法连目标库。
         */
        public String maintenanceUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/postgres";
        }

        /** 不含口令的描述，用于日志。 */
        public String describe() {
            return host + ":" + port + "/" + database + " (user=" + user + ")";
        }
    }

    public static DatabaseTargets fromEnv(Map<String, String> env) {
        JdbcTarget business = new JdbcTarget(
                require(env, "POSTGRES_HOST"),
                intOr(env, "POSTGRES_PORT", 5432),
                require(env, "POSTGRES_DB"),
                require(env, "POSTGRES_USER"),
                require(env, "POSTGRES_PASSWORD"));

        return new DatabaseTargets(business);
    }

    private static String require(Map<String, String> env, String key) {
        String value = DotEnv.get(env, key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少配置项 " + key + "（见 .env.example）");
        }
        return value;
    }

    private static int intOr(Map<String, String> env, String key, int defaultValue) {
        return DotEnv.getInt(env, key, defaultValue);
    }
}
