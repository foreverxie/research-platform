package com.research.platform.modules.report.convert;

import com.research.platform.modules.report.entity.Report;
import com.research.platform.modules.report.vo.ReportVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 报告实体与视图转换
 */
public final class ReportConvert {

    private ReportConvert() {
    }

    /**
     * Entity 转 VO
     *
     * @param report         报告实体
     * @param documentTitles 与 documentIds 顺序一致的文献标题
     */
    public static ReportVO toVO(Report report, List<String> documentTitles) {
        if (report == null) {
            return null;
        }
        ReportVO vo = new ReportVO();
        vo.setId(report.getId());
        vo.setUserId(report.getUserId());
        vo.setTitle(report.getTitle());
        vo.setReportType(report.getReportType());
        vo.setDocumentIds(
                report.getDocumentIds() == null
                        ? new ArrayList<>()
                        : report.getDocumentIds()
        );
        vo.setDocumentTitles(
                documentTitles == null
                        ? new ArrayList<>()
                        : documentTitles
        );
        vo.setStatus(report.getStatus());
        vo.setSummary(report.getSummary());
        vo.setContent(report.getContent());
        vo.setCreatedAt(report.getCreatedAt());
        vo.setUpdatedAt(report.getUpdatedAt());
        return vo;
    }
}
