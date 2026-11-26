package com.research.platform.modules.citation.service.impl;

import com.research.platform.common.exception.BusinessException;
import com.research.platform.modules.citation.service.ICitationService;
import com.research.platform.modules.citation.vo.CitationVO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.service.DocumentAccessService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文献引用服务实现
 *
 * <p>当前只实现 GB/T 7714（顺序编码制）的期刊/会议/学位论文/预印本/专著格式。
 * 引用文本风格与报告模块「附：引用信息」保持一致（英文标点、作者用逗号分隔）。</p>
 */
@Service
public class CitationServiceImpl implements ICitationService {

    private static final String STYLE_GB7714 = "gb7714";

    /**
     * GB/T 7714 规定超过 3 位作者时只列前 3 位，后加「等」
     */
    private static final int MAX_LISTED_AUTHORS = 3;

    private static final String UNTITLED_DOCUMENT = "未命名文献";

    /**
     * 单篇引用固定使用 [1] 作为序号，与报告「附：引用信息」的编号风格一致
     */
    private static final String REFERENCE_NO = "[1] ";

    private final DocumentAccessService documentAccessService;

    public CitationServiceImpl(DocumentAccessService documentAccessService) {
        this.documentAccessService = documentAccessService;
    }

    @Override
    public CitationVO buildCitation(Long documentId, String style) {
        String normalizedStyle = normalizeStyle(style);
        // 统一通过跨模块契约做归属校验，越权/不存在均按 404 处理
        Document document = documentAccessService.requireOwned(documentId);

        CitationVO vo = new CitationVO();
        vo.setStyle(normalizedStyle);
        vo.setText(formatGb7714(document));
        return vo;
    }

    /**
     * 仅支持 gb7714，缺省即 gb7714
     */
    private String normalizeStyle(String style) {
        if (style == null || style.trim().isEmpty()) {
            return STYLE_GB7714;
        }
        String normalized = style.trim().toLowerCase(Locale.ROOT);
        if (!STYLE_GB7714.equals(normalized)) {
            throw new BusinessException(400, "暂仅支持 GB/T 7714 引用格式（style=gb7714）");
        }
        return normalized;
    }

    /**
     * 生成 GB/T 7714 顺序编码制引用：
     * {@code [1] 第一作者, 第二作者, 第三作者, 等. 题名[J]. 刊名, 年份. DOI: 10.xxxx.}
     *
     * <p>缺失字段直接省略，不输出 null；题名缺失时用「未命名文献」兜底。</p>
     */
    private String formatGb7714(Document document) {
        StringBuilder sb = new StringBuilder(REFERENCE_NO);

        List<String> authors = authorList(document);
        if (!authors.isEmpty()) {
            sb.append(formatAuthors(authors)).append(". ");
        }

        sb.append(documentTitle(document))
                .append('[').append(documentTypeCode(document.getDocumentType())).append(']')
                .append('.');

        // 刊名（会议名）与年份组成出版信息，缺失的整段省略
        List<String> publicationParts = new ArrayList<>(2);
        if (hasText(document.getJournal())) {
            publicationParts.add(document.getJournal().trim());
        }
        if (document.getPublicationYear() != null) {
            publicationParts.add(String.valueOf(document.getPublicationYear()));
        }
        if (!publicationParts.isEmpty()) {
            sb.append(' ').append(String.join(", ", publicationParts)).append('.');
        }

        if (hasText(document.getDoi())) {
            sb.append(" DOI: ").append(normalizeDoi(document.getDoi())).append('.');
        }

        return sb.toString();
    }

    /**
     * 作者超过 3 人时取前 3 人 + 「, 等」
     */
    private String formatAuthors(List<String> authors) {
        if (authors.size() <= MAX_LISTED_AUTHORS) {
            return String.join(", ", authors);
        }
        return String.join(", ", authors.subList(0, MAX_LISTED_AUTHORS)) + ", 等";
    }

    /**
     * 文献类型 → GB/T 7714 文献类型标识
     */
    private String documentTypeCode(String documentType) {
        if (documentType == null) {
            return "Z";
        }
        switch (documentType.trim().toUpperCase(Locale.ROOT)) {
            case "JOURNAL":
                return "J";
            case "CONFERENCE":
                return "C";
            case "THESIS":
                return "D";
            case "PREPRINT":
                return "EB/OL";
            case "BOOK":
                return "M";
            default:
                return "Z";
        }
    }

    /**
     * 作者列表（过滤空值、去首尾空白）
     */
    private List<String> authorList(Document document) {
        List<String> authors = new ArrayList<>();
        if (document == null || document.getAuthors() == null) {
            return authors;
        }
        for (String author : document.getAuthors()) {
            if (hasText(author)) {
                authors.add(author.trim());
            }
        }
        return authors;
    }

    /**
     * DOI 统一为裸 DOI，兼容 https://doi.org/xxx 与 doi:xxx 写法
     */
    private String normalizeDoi(String doi) {
        String value = doi.trim();
        String lower = value.toLowerCase(Locale.ROOT);

        int doiOrgIndex = lower.indexOf("doi.org/");
        if (doiOrgIndex >= 0) {
            return value.substring(doiOrgIndex + "doi.org/".length()).trim();
        }
        if (lower.startsWith("doi:")) {
            return value.substring("doi:".length()).trim();
        }
        return value;
    }

    private String documentTitle(Document document) {
        if (document == null || !hasText(document.getTitle())) {
            return UNTITLED_DOCUMENT;
        }
        return document.getTitle().trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
