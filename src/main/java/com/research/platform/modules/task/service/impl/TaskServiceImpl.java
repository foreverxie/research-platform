package com.research.platform.modules.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.research.platform.common.enums.TaskStatus;
import com.research.platform.common.enums.TaskType;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.common.result.PageResult;
import com.research.platform.common.vo.TaskVO;
import com.research.platform.infrastructure.ai.AiTaskRunner;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.task.convert.TaskConvert;
import com.research.platform.modules.task.dto.TaskQueryDTO;
import com.research.platform.modules.task.entity.AiTask;
import com.research.platform.modules.task.entity.AiTaskLog;
import com.research.platform.modules.task.mapper.AiTaskLogMapper;
import com.research.platform.modules.task.mapper.AiTaskMapper;
import com.research.platform.modules.task.service.ITaskService;
import com.research.platform.modules.task.vo.TaskLogVO;
import com.research.platform.modules.task.vo.AdminTaskVO;
import com.research.platform.modules.user.entity.User;
import com.research.platform.modules.user.mapper.UserMapper;
import com.research.platform.utils.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * AI 任务服务实现
 *
 * <p>负责任务的创建、提交、重试、取消与查询。任务状态的实际推进由
 * {@link AiTaskRunner} 异步完成，本类只保证“事务提交后再提交异步任务”，
 * 避免异步线程读到尚未提交的旧数据。</p>
 */
@Service
public class TaskServiceImpl extends ServiceImpl<AiTaskMapper, AiTask> implements ITaskService {

    private static final DateTimeFormatter TASK_NO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /**
     * 关键字按文献标题匹配时，最多纳入的文献数量，避免 IN 条件过长
     */
    private static final int KEYWORD_DOCUMENT_LIMIT = 500;

    private final AiTaskMapper aiTaskMapper;
    private final AiTaskLogMapper aiTaskLogMapper;
    private final DocumentMapper documentMapper;
    private final AiTaskRunner aiTaskRunner;
    private final UserMapper userMapper;

    public TaskServiceImpl(
            AiTaskMapper aiTaskMapper,
            AiTaskLogMapper aiTaskLogMapper,
            DocumentMapper documentMapper,
            AiTaskRunner aiTaskRunner,
            UserMapper userMapper
    ) {
        this.aiTaskMapper = aiTaskMapper;
        this.aiTaskLogMapper = aiTaskLogMapper;
        this.documentMapper = documentMapper;
        this.aiTaskRunner = aiTaskRunner;
        this.userMapper = userMapper;
    }

    /* ------------------------------------------------------------------ */
    /* ITaskService：跨模块契约                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 创建任务并在事务提交后异步执行
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiTask createTask(Long userId, Long documentId, TaskType taskType, Map<String, Object> requestParams) {
        if (userId == null) {
            throw new BusinessException(400, "任务所属用户不能为空");
        }
        if (taskType == null) {
            throw new BusinessException(400, "任务类型不能为空");
        }

        AiTask task = new AiTask();
        task.setTaskNo(generateTaskNo());
        task.setUserId(userId);
        task.setDocumentId(documentId);
        task.setTaskType(taskType.name());
        task.setStatus(TaskStatus.PENDING.name());
        task.setProgress(0);
        task.setCurrentStage("等待执行");
        task.setRequestParams(requestParams);
        task.setRetryCount(0);
        task.setCreatedAt(LocalDateTime.now());

        aiTaskMapper.insert(task);

        // 事务未提交时异步线程可能读到旧数据，因此提交后再投递
        submitAfterCommit(task.getId());

        return task;
    }

    /**
     * 更新执行进度（仅处理中的任务生效，避免覆盖取消/失败状态）
     */
    @Override
    public void updateProgress(Long taskId, int progress, String stage) {
        if (taskId == null) {
            return;
        }

        int safeProgress = Math.max(1, Math.min(99, progress));

        aiTaskMapper.update(null, new LambdaUpdateWrapper<AiTask>()
                .eq(AiTask::getId, taskId)
                .eq(AiTask::getStatus, TaskStatus.PROCESSING.name())
                .set(AiTask::getProgress, safeProgress)
                .set(AiTask::getCurrentStage, stage));
    }

    /**
     * 提交任务到异步执行器
     */
    @Override
    public void submit(Long taskId) {
        if (taskId == null) {
            throw new BusinessException(400, "任务ID不能为空");
        }
        aiTaskRunner.run(taskId);
    }

    /**
     * 重试失败或已取消的任务（复用同一条任务记录）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiTask retry(Long taskId) {
        Long userId = CurrentUser.getCurrentUserId();
        AiTask task = requireOwned(taskId, userId);

        return retryTask(task);
    }

    private AiTask retryTask(AiTask task) {
        Long taskId = task.getId();

        String status = task.getStatus();
        if (!TaskStatus.FAILED.name().equals(status) && !TaskStatus.CANCELLED.name().equals(status)) {
            throw new BusinessException(409, "仅失败或已取消的任务可以重试");
        }

        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();

        // 需要把 error_message/started_at/completed_at 清空为 NULL，
        // updateById 默认忽略 null 字段，因此使用 UpdateWrapper 显式赋值
        LambdaUpdateWrapper<AiTask> updateWrapper = new LambdaUpdateWrapper<AiTask>()
                .eq(AiTask::getId, taskId)
                .set(AiTask::getStatus, TaskStatus.PENDING.name())
                .set(AiTask::getProgress, 0)
                .set(AiTask::getCurrentStage, "等待重试资源")
                .set(AiTask::getErrorMessage, null)
                .set(AiTask::getRetryCount, retryCount + 1)
                .set(AiTask::getStartedAt, null)
                .set(AiTask::getCompletedAt, null);

        aiTaskMapper.update(null, updateWrapper);

        AiTask saved = aiTaskMapper.selectById(taskId);
        submitAfterCommit(taskId);
        return saved;
    }

    /**
     * 取消待处理或处理中的任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiTask cancel(Long taskId) {
        Long userId = CurrentUser.getCurrentUserId();
        AiTask task = requireOwned(taskId, userId);

        return cancelTask(task);
    }

    private AiTask cancelTask(AiTask task) {
        Long taskId = task.getId();

        String status = task.getStatus();
        if (!TaskStatus.PENDING.name().equals(status) && !TaskStatus.PROCESSING.name().equals(status)) {
            throw new BusinessException(409, "该任务当前状态不可取消");
        }

        LocalDateTime now = LocalDateTime.now();

        aiTaskMapper.update(null, new LambdaUpdateWrapper<AiTask>()
                .eq(AiTask::getId, taskId)
                .set(AiTask::getStatus, TaskStatus.CANCELLED.name())
                .set(AiTask::getCurrentStage, "已由用户取消")
                .set(AiTask::getCompletedAt, now));

        AiTaskLog taskLog = new AiTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setStage("用户取消");
        taskLog.setStatus(TaskStatus.CANCELLED.name());
        taskLog.setMessage("任务被用户取消");
        taskLog.setDurationMs(0L);
        taskLog.setCreatedAt(now);
        aiTaskLogMapper.insert(taskLog);

        return aiTaskMapper.selectById(taskId);
    }

    /** 管理员跨用户分页查看任务 */
    public PageResult<AdminTaskVO> pageAdminTasks(TaskQueryDTO query) {
        Page<AiTask> page = new Page<>(query.pageNo(), query.pageSize());
        QueryWrapper<AiTask> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq("status", query.getStatus().trim());
        }
        if (StringUtils.hasText(query.getTaskType())) {
            wrapper.eq("task_type", query.getTaskType().trim());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            List<Long> documentIds = documentMapper.selectList(new QueryWrapper<Document>()
                            .select("id").like("title", keyword).last("LIMIT " + KEYWORD_DOCUMENT_LIMIT))
                    .stream().map(Document::getId).toList();
            wrapper.and(condition -> {
                condition.like("task_no", keyword).or().like("error_message", keyword);
                if (!documentIds.isEmpty()) condition.or().in("document_id", documentIds);
            });
        }
        wrapper.orderByDesc("created_at").orderByDesc("id");
        Page<AiTask> result = aiTaskMapper.selectPage(page, wrapper);
        List<TaskVO> baseRecords = toVOList(result.getRecords());
        List<Long> userIds = result.getRecords().stream().map(AiTask::getUserId).distinct().toList();
        Map<Long, String> usernames = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        List<AdminTaskVO> records = new java.util.ArrayList<>(baseRecords.size());
        for (int index = 0; index < baseRecords.size(); index++) {
            TaskVO base = baseRecords.get(index);
            AiTask task = result.getRecords().get(index);
            AdminTaskVO vo = new AdminTaskVO();
            vo.setId(base.getId());
            vo.setTaskNo(base.getTaskNo());
            vo.setDocumentId(base.getDocumentId());
            vo.setDocumentTitle(base.getDocumentTitle());
            vo.setTaskType(base.getTaskType());
            vo.setStatus(base.getStatus());
            vo.setProgress(base.getProgress());
            vo.setCurrentStage(base.getCurrentStage());
            vo.setErrorMessage(base.getErrorMessage());
            vo.setRetryCount(base.getRetryCount());
            vo.setCreatedAt(base.getCreatedAt());
            vo.setStartedAt(base.getStartedAt());
            vo.setCompletedAt(base.getCompletedAt());
            vo.setUserId(task.getUserId());
            vo.setOwnerUsername(usernames.get(task.getUserId()));
            records.add(vo);
        }
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    /** 管理员跨用户查看任务日志 */
    public List<TaskLogVO> listAdminLogs(Long taskId) {
        if (taskId == null || aiTaskMapper.selectById(taskId) == null) {
            throw new BusinessException(404, "任务不存在");
        }
        return aiTaskLogMapper.selectList(new QueryWrapper<AiTaskLog>()
                        .eq("task_id", taskId).orderByAsc("created_at").orderByAsc("id"))
                .stream().map(TaskConvert::toLogVO).toList();
    }

    /* ------------------------------------------------------------------ */
    /* 任务查询                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 分页查询当前用户的任务
     */
    public PageResult<TaskVO> pageTasks(TaskQueryDTO query) {
        Long userId = CurrentUser.getCurrentUserId();

        Page<AiTask> page = new Page<>(query.pageNo(), query.pageSize());

        QueryWrapper<AiTask> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);

        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq("status", query.getStatus().trim());
        }
        if (StringUtils.hasText(query.getTaskType())) {
            wrapper.eq("task_type", query.getTaskType().trim());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            List<Long> documentIds = findDocumentIdsByTitle(userId, keyword);
            wrapper.and(condition -> {
                condition.like("task_no", keyword);
                if (!documentIds.isEmpty()) {
                    condition.or().in("document_id", documentIds);
                }
            });
        }

        wrapper.orderByDesc("created_at").orderByDesc("id");

        Page<AiTask> result = aiTaskMapper.selectPage(page, wrapper);
        List<TaskVO> records = toVOList(result.getRecords());

        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    /**
     * 查询任务详情
     */
    public TaskVO getTask(Long taskId) {
        Long userId = CurrentUser.getCurrentUserId();
        AiTask task = requireOwned(taskId, userId);

        TaskVO vo = TaskConvert.toVO(task);
        if (task.getDocumentId() != null) {
            vo.setDocumentTitle(loadDocumentTitles(List.of(task.getDocumentId()))
                    .get(task.getDocumentId()));
        }
        return vo;
    }

    /**
     * 查询任务执行日志（按创建时间升序）
     */
    public List<TaskLogVO> listLogs(Long taskId) {
        Long userId = CurrentUser.getCurrentUserId();
        requireOwned(taskId, userId);

        List<AiTaskLog> logs = aiTaskLogMapper.selectList(new QueryWrapper<AiTaskLog>()
                .eq("task_id", taskId)
                .orderByAsc("created_at")
                .orderByAsc("id"));

        return logs.stream().map(TaskConvert::toLogVO).toList();
    }

    /* ------------------------------------------------------------------ */
    /* 内部方法                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 校验任务归属：不存在或不属于当前用户统一返回 404，避免暴露他人数据
     */
    private AiTask requireOwned(Long taskId, Long userId) {
        AiTask task = taskId == null ? null : aiTaskMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getUserId(), userId)) {
            throw new BusinessException(404, "任务不存在");
        }
        return task;
    }

    /**
     * 事务提交后再提交异步任务；没有事务时直接提交
     */
    private void submitAfterCommit(Long taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    aiTaskRunner.run(taskId);
                }
            });
        } else {
            aiTaskRunner.run(taskId);
        }
    }

    /**
     * 生成任务编号：TASK + yyyyMMddHHmmssSSS + 3 位随机数
     */
    private String generateTaskNo() {
        String timestamp = LocalDateTime.now().format(TASK_NO_FORMATTER);
        int random = ThreadLocalRandom.current().nextInt(1000);
        return "TASK" + timestamp + String.format("%03d", random);
    }

    /**
     * 按文献标题（当前用户）查找候选文献ID
     */
    private List<Long> findDocumentIdsByTitle(Long userId, String keyword) {
        List<Document> documents = documentMapper.selectList(new QueryWrapper<Document>()
                .select("id")
                .eq("user_id", userId)
                .like("title", keyword)
                .last("LIMIT " + KEYWORD_DOCUMENT_LIMIT));

        return documents.stream().map(Document::getId).toList();
    }

    /**
     * 批量补全文献标题，避免逐条查询
     */
    private Map<Long, String> loadDocumentTitles(Collection<Long> documentIds) {
        Set<Long> ids = documentIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Document> documents = documentMapper.selectBatchIds(ids);
        return documents.stream()
                .filter(document -> document.getId() != null && document.getTitle() != null)
                .collect(Collectors.toMap(Document::getId, Document::getTitle, (left, right) -> left));
    }

    /**
     * 实体列表转视图列表，并批量补全文献标题
     */
    private List<TaskVO> toVOList(List<AiTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }

        Map<Long, String> titles = loadDocumentTitles(tasks.stream()
                .map(AiTask::getDocumentId)
                .filter(Objects::nonNull)
                .toList());

        return tasks.stream().map(task -> {
            TaskVO vo = TaskConvert.toVO(task);
            if (task.getDocumentId() != null) {
                vo.setDocumentTitle(titles.get(task.getDocumentId()));
            }
            return vo;
        }).toList();
    }
}
