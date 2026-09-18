package com.remasteragent.web.api.dto;

import com.remasteragent.core.writeback.WriteBackReport;

import java.time.Instant;
import java.util.List;

/**
 * 「变更回写」的预检报告 / 执行结果 —— 对外形状。
 *
 * <p>沿用 core 里 {@link WriteBackReport} 的设计：预检与执行共用一张表，
 * 前端只渲染一种卡片。这里再包一层 DTO 而不是直接把 core 的类型序列化出去，
 * 理由与其余视图一致 —— 对外契约要能独立演进，core 里加减内部字段不该悄悄改变 API。
 *
 * <h2>注意：{@code ready()} / {@code applied()} <b>不会</b>出现在 JSON 里</h2>
 * <p>它们是 record 的<b>非组件</b>方法（名字也没有 {@code get}/{@code is} 前缀），
 * Jackson 序列化 record 时只认组件，所以这两个便捷判定在线上载荷里根本不存在。
 * 本项目在 {@code TaskMetrics} 上已经为此付过一次代价：派生方法序列化时全丢，
 * 前端拿到的对象少几个键，指标面板静默清零，而任务本身完全正常。
 *
 * <p>因此对外的约定是：<b>判定一律从源数据现算</b> ——
 * 「预检通过」= {@code blocked} 为空；「已经写回」= {@code appliedAt != null}。
 * 计数与时间戳永远都在，布尔结论是能被算出来的。
 * {@code WriteBackReportViewShapeTest} 钉住这条约定，防止有人日后给它们加上
 * {@code get} 前缀而让载荷多出两个可能漂移的布尔。
 *
 * @param taskId                  任务 id
 * @param projectRoot             源工程根目录（写回目标）
 * @param workspace               沙箱工作目录（内容来源）
 * @param backupDir               备份目录；回滚就是把它整棵拷回去
 * @param files                   将要 / 已经写回的文件
 * @param blocked                 拦路项 —— <b>非空即拒绝执行</b>，一个字节都不写
 * @param warnings                放行但不放心的提示
 * @param suggestedCommitMessage  建议的提交信息（提交与否由人决定）
 * @param appliedAt               实际写回时间；null 表示只做了预检
 */
public record WriteBackReportView(
        long taskId,
        String projectRoot,
        String workspace,
        String backupDir,
        List<FileEntry> files,
        List<Blocked> blocked,
        List<String> warnings,
        String suggestedCommitMessage,
        Instant appliedAt
) {

    /**
     * 一个待写回 / 已写回的文件。
     *
     * @param baseOk 该文件的「改写前基线」与源工程现状是否一致
     */
    public record FileEntry(String filePath, long bytes, String sha256, boolean baseOk) {
    }

    /**
     * 拦路项。
     *
     * @param code    机器可判的代号（{@code TASK_NOT_SUCCEEDED} / {@code DIRTY_WORKING_TREE} 等）
     * @param message 给人看的说明，包含「怎么解决」
     */
    public record Blocked(String code, String message) {
    }

    /** 预检是否通过。 */
    public boolean ready() {
        return blocked.isEmpty();
    }

    /** 是否已经真的写回了。 */
    public boolean applied() {
        return appliedAt != null;
    }

    public static WriteBackReportView of(WriteBackReport report) {
        return new WriteBackReportView(
                report.taskId(),
                report.projectRoot(),
                report.workspace(),
                report.backupDir(),
                report.files().stream()
                        .map(file -> new FileEntry(file.filePath(), file.bytes(), file.sha256(), file.baseOk()))
                        .toList(),
                report.blocked().stream()
                        .map(blocked -> new Blocked(blocked.code(), blocked.message()))
                        .toList(),
                List.copyOf(report.warnings()),
                report.suggestedCommitMessage(),
                report.appliedAt());
    }
}
