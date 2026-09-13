package com.remasteragent.web.api.dto;

import com.remasteragent.common.domain.MigrationTask;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建迁移任务的请求。
 *
 * <p>只有三个字段，因为阶段 1 的输入就是「一个本地可信工程 + 里面的一个文件」。
 * 不支持远端仓库是刻意的取舍：拉代码、鉴权、清理这些都不是这个项目要证明的能力，
 * 而它们会稀释掉真正想展示的东西（编排 + 验证闭环）。
 *
 * @param projectRoot 被测工程根目录的绝对路径（必须含 pom.xml）
 * @param entryFile   本轮要改写的文件，相对 projectRoot 的路径，必须以 .java 结尾
 * @param targetJdk   目标 JDK 版本，缺省 21
 */
public record CreateTaskRequest(
        @NotBlank(message = "projectRoot 不能为空")
        String projectRoot,

        @NotBlank(message = "entryFile 不能为空")
        String entryFile,

        @Min(value = 17, message = "targetJdk 至少为 17")
        Integer targetJdk
) {

    public int targetJdkOrDefault() {
        return targetJdk == null ? MigrationTask.DEFAULT_TARGET_JDK : targetJdk;
    }
}
