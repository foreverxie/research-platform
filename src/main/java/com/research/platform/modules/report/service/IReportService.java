package com.research.platform.modules.report.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.research.platform.common.dto.PageQueryDTO;
import com.research.platform.common.result.PageResult;
import com.research.platform.modules.report.dto.ReportCreateDTO;
import com.research.platform.modules.report.entity.Report;
import com.research.platform.modules.report.vo.ExportFile;
import com.research.platform.modules.report.vo.ReportVO;

/**
 * 分析报告服务
 */
public interface IReportService extends IService<Report> {

    /**
     * 分页查询当前用户的报告
     */
    PageResult<ReportVO> page(PageQueryDTO query);

    /**
     * 查询报告详情
     */
    ReportVO detail(Long id);

    /**
     * 基于已保存的 AI 分析结果与文献元数据生成报告
     */
    ReportVO create(ReportCreateDTO dto);

    /**
     * 导出报告
     *
     * @param id     报告ID（校验归属，不存在按 404 处理）
     * @param format markdown/md 或 docx，默认 markdown
     * @return 文件名 + 字节内容
     */
    ExportFile export(Long id, String format);

    /**
     * 删除报告（逻辑删除）
     */
    void delete(Long id);
}
