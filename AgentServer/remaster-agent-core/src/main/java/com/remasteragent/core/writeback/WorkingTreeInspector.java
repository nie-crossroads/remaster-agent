package com.remasteragent.core.writeback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 「这个目录的 git 工作区干不干净」的检查 —— 回写前的一道保险。
 *
 * <h2>为什么必须查工作区，而不是只查文件哈希</h2>
 * <p>逐文件比对「改写前的指纹」能挡住「我改的那个文件后来被人动过」，但挡不住另一种情况：
 * 用户自己正在源工程里改别的东西（改了 5 个文件、还没提交）。这时候把 AI 的产出写进去，
 * 两拨改动就混在同一个工作区里 —— 想回滚时 {@code git checkout} 会一起丢掉用户的手工修改，
 * 想 review 时 diff 里分不清哪些是 Agent 写的。<b>回写必须是「一次干净的落盘」</b>，
 * 混进去就没法还原。
 *
 * <p>做成接口是为了让编排层的单测不依赖 git 二进制：测试注入桩件即可，
 * 真跑进程的那条路径由带 {@code assumeTrue} 的用例单独覆盖。
 */
public interface WorkingTreeInspector {

    /**
     * @param gitRepo 该目录是否在 git 工作区内（无法判定时为 false）
     * @param clean   工作区是否干净（无未提交改动）；非 git 仓库恒为 true
     * @param detail  细节说明（分支名 / 脏文件数 / 失败原因），用于回写报告
     */
    record Result(boolean gitRepo, boolean clean, String detail) {

        static Result notRepo(String detail) {
            return new Result(false, true, detail);
        }
    }

    Result inspect(Path projectRoot);

    /**
     * 真跑 {@code git} 的实现。
     *
     * <p>只读两条命令，不做任何写操作：{@code rev-parse --is-inside-work-tree} 判断是不是工作区，
     * {@code status --porcelain} 取未提交改动。用 {@code -C} 指定目录而不是切进程工作目录 ——
     * 后者会污染整个 JVM 的 cwd（这正是沙箱路径漂移那一课）。
     */
    static WorkingTreeInspector git() {
        return GitProcessInspector.INSTANCE;
    }

    /** 完全不检查（只在明确知道自己在做什么时使用，例如把回写目标指向一次性目录的验收脚本）。 */
    WorkingTreeInspector NONE = root -> Result.notRepo("未启用工作区检查");

    /** 默认实现：调用 git 可执行文件。 */
    final class GitProcessInspector implements WorkingTreeInspector {

        private static final Logger log = LoggerFactory.getLogger(GitProcessInspector.class);
        private static final GitProcessInspector INSTANCE = new GitProcessInspector();

        /** git 命令超时。正常是毫秒级；超时说明环境异常（如网络盘、杀软拦截），不该让页面挂住。 */
        private static final long TIMEOUT_SECONDS = 10;

        @Override
        public Result inspect(Path projectRoot) {
            ExecResult inRepo = exec(projectRoot, "rev-parse", "--is-inside-work-tree");
            if (inRepo.exitCode() != 0 || !inRepo.stdout().trim().equals("true")) {
                // 非 git 目录不是错误：回写仍然允许（哈希校验还在），只是少了版本控制这道兜底。
                // 这里如实返回 false，由上层决定怎么提示，而不是替用户假装检查过了。
                return Result.notRepo(inRepo.failed()
                        ? "无法执行 git（" + inRepo.stderr().trim() + "）"
                        : "不是 git 工作区，回写后无版本控制兜底");
            }

            ExecResult status = exec(projectRoot, "status", "--porcelain");
            if (status.failed()) {
                return new Result(true, false, "git status 执行失败：" + status.stderr().trim());
            }
            long dirty = status.stdout().lines().filter(line -> !line.isBlank()).count();
            return new Result(true, dirty == 0,
                    dirty == 0 ? "git 工作区干净" : "git 工作区有 " + dirty + " 处未提交改动");
        }

        private ExecResult exec(Path directory, String... args) {
            List<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(directory.toString());
            command.addAll(List.of(args));
            try {
                Process process = new ProcessBuilder(command)
                        .redirectErrorStream(false)
                        .start();
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return new ExecResult(-1, stdout, "git 命令超时");
                }
                return new ExecResult(process.exitValue(), stdout, stderr);
            } catch (IOException e) {
                // git 没装 / 不在 PATH：这是一条正常可能，按「无法判定」返回，由报告如实说明
                return new ExecResult(-1, "", String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ExecResult(-1, "", "等待 git 被中断");
            }
        }

        private record ExecResult(int exitCode, String stdout, String stderr) {
            boolean failed() {
                return exitCode != 0;
            }
        }
    }
}
