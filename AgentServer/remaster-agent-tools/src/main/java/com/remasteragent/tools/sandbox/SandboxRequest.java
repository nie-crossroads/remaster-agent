package com.remasteragent.tools.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 一次沙箱执行请求。
 *
 * <p><b>接口契约（换成容器实现时必须遵守）</b>：{@code command} 里的所有路径都必须
 * <b>相对于 {@code workspace}</b>，不要出现宿主机的绝对路径。这样容器实现才能把
 * {@code workspace} 挂载到容器内的固定位置，然后原样执行同一条命令，上层无需改动。
 *
 * @param workspace     工作目录（宿主侧）。被测工程会被复制到这里，原仓库绝不被就地修改
 * @param command       要执行的命令，路径相对 workspace
 * @param timeout       硬超时；超时后进程会被强制杀死
 * @param memoryLimitMb 内存上限（MB）
 * @param environment   额外的环境变量，会叠加在继承来的环境之上
 */
public record SandboxRequest(
        Path workspace,
        List<String> command,
        Duration timeout,
        int memoryLimitMb,
        Map<String, String> environment
) {

    public static Builder builder(Path workspace, List<String> command) {
        return new Builder(workspace, command);
    }

    public static final class Builder {
        private final Path workspace;
        private final List<String> command;
        private Duration timeout = Duration.ofMinutes(10);
        private int memoryLimitMb = 1024;
        private Map<String, String> environment = Map.of();

        private Builder(Path workspace, List<String> command) {
            this.workspace = workspace;
            this.command = command;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder memoryLimitMb(int memoryLimitMb) {
            this.memoryLimitMb = memoryLimitMb;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = environment;
            return this;
        }

        public SandboxRequest build() {
            return new SandboxRequest(workspace, command, timeout, memoryLimitMb, environment);
        }
    }
}
