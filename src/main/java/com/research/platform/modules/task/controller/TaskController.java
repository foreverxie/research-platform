package com.research.platform.modules.task.controller;

import com.research.platform.common.result.PageResult;
import com.research.platform.common.result.Result;
import com.research.platform.common.vo.TaskVO;
import com.research.platform.modules.task.dto.TaskQueryDTO;
import com.research.platform.modules.task.entity.AiTask;
import com.research.platform.modules.task.service.impl.TaskServiceImpl;
import com.research.platform.modules.task.vo.TaskLogVO;
import com.research.platform.modules.task.vo.AdminTaskVO;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 任务接口
 *
 * <p>说明：跨模块契约 {@code ITaskService} 只定义了任务创建与状态流转，
 * 查询能力由实现类补充，因此这里直接注入实现类。</p>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskServiceImpl taskService;

    public TaskController(TaskServiceImpl taskService) {
        this.taskService = taskService;
    }

    /**
     * 任务列表：按创建时间倒序，支持状态、类型、关键字筛选
     */
    @GetMapping
    public Result<PageResult<TaskVO>> listTasks(@Valid TaskQueryDTO query) {
        return Result.success(taskService.pageTasks(query));
    }

    /**
     * 任务详情
     */
    @GetMapping("/{id}")
    public Result<TaskVO> getTask(@PathVariable Long id) {
        return Result.success(taskService.getTask(id));
    }

    /**
     * 任务执行日志
     */
    @GetMapping("/{id}/logs")
    public Result<List<TaskLogVO>> listLogs(@PathVariable Long id) {
        return Result.success(taskService.listLogs(id));
    }

    /**
     * 重试失败或已取消的任务
     */
    @PostMapping("/{id}/retry")
    public Result<TaskVO> retry(@PathVariable Long id) {
        AiTask task = taskService.retry(id);
        return Result.success("任务已重新提交", toVO(task));
    }

    /**
     * 取消待处理或处理中的任务
     */
    @PostMapping("/{id}/cancel")
    public Result<TaskVO> cancel(@PathVariable Long id) {
        AiTask task = taskService.cancel(id);
        return Result.success("任务已取消", toVO(task));
    }

    /** 管理员跨用户查看任务 */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin")
    public Result<PageResult<AdminTaskVO>> adminList(@Valid TaskQueryDTO query) {
        return Result.success(taskService.pageAdminTasks(query));
    }

    /** 管理员查看任意用户任务的执行日志 */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/{id}/logs")
    public Result<List<TaskLogVO>> adminLogs(@PathVariable Long id) {
        return Result.success(taskService.listAdminLogs(id));
    }

    /**
     * 返回前补全文献标题
     */
    private TaskVO toVO(AiTask task) {
        return task == null ? null : taskService.getTask(task.getId());
    }
}
