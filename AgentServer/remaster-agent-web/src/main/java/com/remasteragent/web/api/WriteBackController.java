package com.remasteragent.web.api;

import com.remasteragent.core.writeback.SourceWriteBackService;
import com.remasteragent.core.writeback.WriteBackReport;
import com.remasteragent.web.api.dto.WriteBackReportView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 变更回写：把沙箱里已验证过的产出落回源工程。
 *
 * <h2>为什么单独一个 Controller，而不是塞进 {@link TaskController}</h2>
 * <p>它对应的是一个与「编排」完全不同的动作：编排是 Worker 在沙箱里跑来跑去，
 * 回写是<b>人点一下、API 进程直接动源工程的磁盘文件</b>。这两件事的失败方式、
 * 权限边界、审计要求都不一样。放在一起会让 TaskController 的构造函数再长一截，
 * 也会让「这个接口会不会改我的文件」这个最重要的区分被淹没在十几个端点里。
 *
 * <h2>两个端点：先看清单，再动手</h2>
 * <ul>
 *   <li>{@code GET} 预检 —— 只读：列出将写哪几个文件、有没有拦路的。它<b>不写任何东西</b>。</li>
 *   <li>{@code POST} 执行 —— 再预检一遍，通过才写；先备份再覆盖，最后落一行审计。</li>
 * </ul>
 *
 * <h2>为什么 POST 被拒绝时也返回 200</h2>
 * <p>拦路项是这个结果<b>本身的内容</b>（「工作区不干净，请先 git stash」），
 * 前端要把它原样渲染成一张卡片。若改成 409 + 错误体，同一份信息就有了两种形状，
 * 而两处各写一遍迟早会不一致。客户端靠 {@code applied} / {@code ready} 两个布尔判断结果，
 * 不靠 HTTP 状态码猜。这与 {@code /gate/reject} 返回 200 是同一条约定。
 *
 * <h2>关于「回写到 GitHub」</h2>
 * <p>本接口<b>只写本地工作区文件</b>，绝不 commit、绝不 push。源工程目录通常本身就是一份
 * git 工作区，写回文件即等同于写进仓库工作区；而提交是人的动作 —— Agent 不该持有远端凭据，
 * 也不该替人决定什么进版本历史。建议的提交信息会随报告返回，供人直接采用。
 */
@RestController
@RequestMapping("/api/tasks/{id}/write-back")
public class WriteBackController {

    private static final Logger log = LoggerFactory.getLogger(WriteBackController.class);

    private final SourceWriteBackService writeBackService;
    private final TaskQueryService queryService;

    public WriteBackController(SourceWriteBackService writeBackService, TaskQueryService queryService) {
        this.writeBackService = writeBackService;
        this.queryService = queryService;
    }

    /**
     * 预检：只检查、不写盘。
     *
     * <p>单独暴露它，是为了让「应用到源工程」这个危险动作前面永远隔着一张清单 ——
     * 人得先看见「要写 3 个文件到 /path/to/repo，工作区干净」，再决定点不点确认。
     * 直接 POST 也能跑（它自己会再预检一遍），但前端不该那么用。
     */
    @GetMapping
    public WriteBackReportView preflight(@PathVariable long id) {
        requireTask(id);
        WriteBackReport report = writeBackService.preflight(id);
        log.info("任务 #{} 回写预检: ready={} 待写={} 拦路={}", id, report.ready(),
                report.files().size(),
                report.blocked().stream().map(WriteBackReport.Blocked::code).toList());
        return WriteBackReportView.of(report);
    }

    /** 执行回写：备份 → 覆盖 → 落审计。被拦时一个字节都不写，并把拦路项原样返回。 */
    @PostMapping
    public WriteBackReportView apply(@PathVariable long id) {
        requireTask(id);
        WriteBackReport report = writeBackService.apply(id);
        if (report.applied()) {
            log.info("任务 #{} 已回写 {} 个文件到 {}（备份 {}）", id, report.files().size(),
                    report.projectRoot(), report.backupDir());
        } else {
            log.warn("任务 #{} 回写未执行: {}", id,
                    report.blocked().stream().map(WriteBackReport.Blocked::code).toList());
        }
        return WriteBackReportView.of(report);
    }

    /**
     * 任务不存在就 404。
     *
     * <p>{@code preflight} 内部对不存在的任务也会返回一张「TASK_NOT_FOUND」报告 ——
     * 那是给<b>已确认存在</b>的任务用的（比如任务刚被删掉的竞态）。但一个拼错的 id
     * 应该是 404，而不是 200 加一份看不懂的报告：前者一眼知道是路径写错了，
     * 后者会让人以为「这个任务真的回写不了」。
     */
    private void requireTask(long id) {
        queryService.taskSummary(id);
    }
}
