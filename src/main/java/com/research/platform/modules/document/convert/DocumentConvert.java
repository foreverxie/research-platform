package com.research.platform.modules.document.convert;

import com.research.platform.common.vo.AnalysisVO;
import com.research.platform.common.vo.NoteVO;
import com.research.platform.common.vo.RecommendationItemVO;
import com.research.platform.common.vo.TagVO;
import com.research.platform.common.vo.TaskVO;
import com.research.platform.modules.analysis.entity.AiAnalysis;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.entity.DocumentChunk;
import com.research.platform.modules.document.entity.DocumentFile;
import com.research.platform.modules.document.entity.ParseRecord;
import com.research.platform.modules.document.vo.DocumentChunkVO;
import com.research.platform.modules.document.vo.DocumentFileVO;
import com.research.platform.modules.document.vo.DocumentVO;
import com.research.platform.modules.document.vo.ParseRecordVO;
import com.research.platform.modules.note.entity.Note;
import com.research.platform.modules.tag.entity.Tag;
import com.research.platform.modules.task.entity.AiTask;

import java.util.ArrayList;
import java.util.List;

/**
 * 文献模块实体转 VO
 *
 * <p>只做字段搬运，标签、分类名、收藏、文件大小等跨表数据由服务层批量补全。</p>
 */
public final class DocumentConvert {

    private DocumentConvert() {
    }

    /**
     * Document 转列表项 VO（基础字段）
     */
    public static DocumentVO toVO(Document document) {
        if (document == null) {
            return null;
        }

        DocumentVO vo = new DocumentVO();
        vo.setId(document.getId());
        vo.setUserId(document.getUserId());
        vo.setTitle(document.getTitle());
        vo.setAuthors(document.getAuthors());
        vo.setAbstractText(document.getAbstractText());
        vo.setKeywords(document.getKeywords());
        vo.setPublicationYear(document.getPublicationYear());
        vo.setJournal(document.getJournal());
        vo.setDoi(document.getDoi());
        vo.setLanguage(document.getLanguage());
        vo.setDocumentType(document.getDocumentType());
        vo.setCategoryId(document.getCategoryId());
        vo.setSource(document.getSource());
        vo.setParseStatus(document.getParseStatus());
        vo.setCurrentParseRecordId(document.getCurrentParseRecordId());
        vo.setCreatedAt(document.getCreatedAt());
        vo.setUpdatedAt(document.getUpdatedAt());
        vo.setTags(new ArrayList<>());
        vo.setFavorite(false);
        vo.setFileSize(0L);
        vo.setPageCount(0);

        return vo;
    }

    /**
     * 文献文件转 VO
     */
    public static DocumentFileVO toFileVO(DocumentFile file) {
        if (file == null) {
            return null;
        }

        DocumentFileVO vo = new DocumentFileVO();
        vo.setId(file.getId());
        vo.setDocumentId(file.getDocumentId());
        vo.setFileType(file.getFileType());
        vo.setFileName(file.getFileName());
        vo.setStoragePath(file.getStoragePath());
        vo.setFileSize(file.getFileSize());
        vo.setFileHash(file.getFileHash());
        vo.setVersion(file.getVersion());
        vo.setCreatedAt(file.getCreatedAt());

        return vo;
    }

    /**
     * 解析记录转 VO
     */
    public static ParseRecordVO toParseRecordVO(ParseRecord record) {
        if (record == null) {
            return null;
        }

        ParseRecordVO vo = new ParseRecordVO();
        vo.setId(record.getId());
        vo.setDocumentId(record.getDocumentId());
        vo.setTaskId(record.getTaskId());
        vo.setParserName(record.getParserName());
        vo.setParserVersion(record.getParserVersion());
        vo.setStatus(record.getStatus());
        vo.setMarkdownFileId(record.getMarkdownFileId());
        vo.setJsonFileId(record.getJsonFileId());
        vo.setPageCount(record.getPageCount());
        vo.setErrorMessage(record.getErrorMessage());
        vo.setIsCurrent(record.getIsCurrent());
        vo.setCreatedAt(record.getCreatedAt());
        vo.setCompletedAt(record.getCompletedAt());

        return vo;
    }

    /**
     * 文本块转 VO
     */
    public static DocumentChunkVO toChunkVO(DocumentChunk chunk) {
        if (chunk == null) {
            return null;
        }

        DocumentChunkVO vo = new DocumentChunkVO();
        vo.setId(chunk.getId());
        vo.setChunkId(chunk.getChunkId());
        vo.setUserId(chunk.getUserId());
        vo.setDocumentId(chunk.getDocumentId());
        vo.setParseRecordId(chunk.getParseRecordId());
        vo.setChunkIndex(chunk.getChunkIndex());
        vo.setSection(chunk.getSection());
        vo.setPageStart(chunk.getPageStart());
        vo.setPageEnd(chunk.getPageEnd());
        vo.setContent(chunk.getContent());
        vo.setTokenCount(chunk.getTokenCount());
        vo.setEmbeddingStatus(chunk.getEmbeddingStatus());
        vo.setEmbeddingModel(chunk.getEmbeddingModel());
        vo.setCreatedAt(chunk.getCreatedAt());

        return vo;
    }

    /**
     * 文本块列表转 VO 列表
     */
    public static List<DocumentChunkVO> toChunkVOList(List<DocumentChunk> chunks) {
        List<DocumentChunkVO> list = new ArrayList<>();
        if (chunks == null) {
            return list;
        }

        for (DocumentChunk chunk : chunks) {
            list.add(toChunkVO(chunk));
        }

        return list;
    }

    /**
     * Tag 转 TagVO
     */
    public static TagVO toTagVO(Tag tag) {
        if (tag == null) {
            return null;
        }

        TagVO vo = new TagVO();
        vo.setId(tag.getId());
        vo.setName(tag.getName());
        vo.setColor(tag.getColor());
        vo.setCreatedAt(tag.getCreatedAt());

        return vo;
    }

    /**
     * AiAnalysis 转 AnalysisVO
     */
    public static AnalysisVO toAnalysisVO(AiAnalysis analysis) {
        if (analysis == null) {
            return null;
        }

        AnalysisVO vo = new AnalysisVO();
        vo.setId(analysis.getId());
        vo.setDocumentId(analysis.getDocumentId());
        vo.setTaskId(analysis.getTaskId());
        vo.setAnalysisType(analysis.getAnalysisType());
        vo.setStatus(analysis.getStatus());
        vo.setVersion(analysis.getVersion());
        vo.setIsCurrent(analysis.getIsCurrent());
        vo.setSummary(analysis.getSummary());
        vo.setResearchBackground(analysis.getResearchBackground());
        vo.setResearchMethod(analysis.getResearchMethod());
        vo.setInnovationPoints(analysis.getInnovationPoints());
        vo.setExperimentalResults(analysis.getExperimentalResults());
        vo.setConclusion(analysis.getConclusion());
        vo.setKeywords(analysis.getKeywords());
        vo.setModelName(analysis.getModelName());
        vo.setErrorMessage(analysis.getErrorMessage());
        vo.setCreatedAt(analysis.getCreatedAt());
        vo.setCompletedAt(analysis.getCompletedAt());

        return vo;
    }

    /**
     * AiTask 转 TaskVO
     *
     * @param documentTitle 文献标题，前端列表直接展示
     */
    public static TaskVO toTaskVO(AiTask task, String documentTitle) {
        if (task == null) {
            return null;
        }

        TaskVO vo = new TaskVO();
        vo.setId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setDocumentId(task.getDocumentId());
        vo.setDocumentTitle(documentTitle);
        vo.setTaskType(task.getTaskType());
        vo.setStatus(task.getStatus());
        vo.setProgress(task.getProgress());
        vo.setCurrentStage(task.getCurrentStage());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setRetryCount(task.getRetryCount());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setStartedAt(task.getStartedAt());
        vo.setCompletedAt(task.getCompletedAt());

        return vo;
    }

    /**
     * Note 转 NoteVO
     *
     * @param documentTitle 文献标题，前端笔记列表直接展示
     */
    public static NoteVO toNoteVO(Note note, String documentTitle) {
        if (note == null) {
            return null;
        }

        NoteVO vo = new NoteVO();
        vo.setId(note.getId());
        vo.setDocumentId(note.getDocumentId());
        vo.setDocumentTitle(documentTitle);
        vo.setTitle(note.getTitle());
        vo.setContent(note.getContent());
        vo.setPageNumber(note.getPageNumber());
        vo.setChunkId(note.getChunkId());
        vo.setCreatedAt(note.getCreatedAt());
        vo.setUpdatedAt(note.getUpdatedAt());

        return vo;
    }

    /**
     * 文献转相似推荐项
     */
    public static RecommendationItemVO toRecommendationItem(Document document, Double score, String reason) {
        if (document == null) {
            return null;
        }

        RecommendationItemVO vo = new RecommendationItemVO();
        vo.setDocumentId(document.getId());
        vo.setTitle(document.getTitle());
        vo.setAuthors(document.getAuthors());
        vo.setPublicationYear(document.getPublicationYear());
        vo.setJournal(document.getJournal());
        vo.setAbstractText(document.getAbstractText());
        vo.setKeywords(document.getKeywords());
        vo.setScore(score);
        vo.setReason(reason);

        return vo;
    }
}
