package com.research.platform.modules.document.vo;

import com.research.platform.common.vo.AnalysisVO;
import com.research.platform.common.vo.NoteVO;
import com.research.platform.common.vo.RecommendationItemVO;
import com.research.platform.common.vo.TaskVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 文献详情视图
 *
 * <p>在列表项字段的基础上追加文件、解析记录、历史分析、笔记、相关文献
 * 与最新任务，对应前端 {@code DocumentDetail}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DocumentDetailVO extends DocumentVO {

    /**
     * 文献文件列表（原始 PDF、解析产物等）
     */
    private List<DocumentFileVO> files;

    /**
     * 解析记录，按 id 倒序
     */
    private List<ParseRecordVO> parseRecords;

    /**
     * 全部历史分析结果，按创建时间倒序
     */
    private List<AnalysisVO> analyses;

    /**
     * 文献笔记，按更新时间倒序
     */
    private List<NoteVO> notes;

    /**
     * 相关文献推荐
     */
    private List<RecommendationItemVO> related;

    /**
     * 最近一次 AI 任务
     */
    private TaskVO latestTask;
}
