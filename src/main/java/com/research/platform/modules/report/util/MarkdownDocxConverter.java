package com.research.platform.modules.report.util;

import com.research.platform.common.exception.BusinessException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Markdown → DOCX 的轻量转换器
 *
 * <p>报告正文是平台自己拼装的 Markdown，结构可预期，因此这里只做「够用」的转换，
 * 不引入完整的 Markdown 解析器：</p>
 * <ul>
 *     <li>{@code # / ## / ###} → Heading 1 / 2 / 3（四级及以下归入 Heading 3）</li>
 *     <li>{@code >} 引用 → 缩进斜体小字</li>
 *     <li>{@code - / * / 1.} 列表 → 以 {@code •} 开头的列表段落</li>
 *     <li>{@code **粗体**} → 行内加粗（保留其它文字原有粗细）</li>
 *     <li>{@code | a | b |} 表格行 → 用 {@code |} 拼接的普通段落（不做真实表格）</li>
 *     <li>其它非空行 → 普通段落</li>
 * </ul>
 *
 * <p>中文字体统一设置为微软雅黑（同时写入 ascii / hAnsi / eastAsia），
 * 并创建 Heading 样式的定义，保证 Word 中标题层级与导航窗格可用。</p>
 */
public final class MarkdownDocxConverter {

    /**
     * 正文字体（中文、英文统一）
     */
    private static final String FONT_FAMILY = "微软雅黑";

    private static final int BODY_FONT_SIZE = 11;

    private static final int QUOTE_FONT_SIZE = 10;

    private static final int TABLE_FONT_SIZE = 10;

    /**
     * 支持的最大标题级别（四级及以下归入 Heading 3）
     */
    private static final int MAX_HEADING_LEVEL = 3;

    private static final int[] HEADING_FONT_SIZES = {20, 16, 14};

    private static final int HEADING_SPACING_BEFORE = 240;

    private static final int HEADING_SPACING_AFTER = 120;

    private static final int PARAGRAPH_SPACING_AFTER = 120;

    private static final int LIST_INDENT = 360;

    /**
     * A4 页面与页边距（单位：twips，1cm ≈ 567 twips）
     */
    private static final int PAGE_WIDTH_TWIPS = 11906;

    private static final int PAGE_HEIGHT_TWIPS = 16838;

    private static final int PAGE_MARGIN_VERTICAL_TWIPS = 1134;

    private static final int PAGE_MARGIN_HORIZONTAL_TWIPS = 1247;

    private static final int PAGE_HEADER_FOOTER_TWIPS = 851;

    private static final Pattern ORDERED_LIST_PATTERN =
            Pattern.compile("^\\d+[.)]\\s+.*$");

    private static final Pattern LINK_PATTERN =
            Pattern.compile("!?\\[([^\\]]*)]\\([^)]*\\)");

    private MarkdownDocxConverter() {
    }

    /**
     * 把 Markdown 正文转换为 DOCX 字节
     *
     * @param markdown 报告 Markdown 正文，允许为 null
     * @return docx 文件字节
     */
    public static byte[] toDocx(String markdown) {
        String source = markdown == null ? "" : markdown;

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            initStyles(document);
            setupPage(document);

            writeLines(document, source);
            if (document.getParagraphs().isEmpty()) {
                // 正文为空（或只有水平分隔线）时也生成一个可打开的文档
                appendParagraph(document, "（报告正文为空）");
            }

            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(500, "生成 DOCX 文件失败：" + e.getMessage());
        }
    }

    /**
     * 按行渲染 Markdown
     */
    private static void writeLines(XWPFDocument document, String markdown) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');

        for (String rawLine : normalized.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            int headingMarker = countHeadingMarker(line);
            if (headingMarker > 0) {
                appendHeading(document, line, headingMarker);
            } else if (line.startsWith(">")) {
                appendQuote(document, stripQuoteMarker(line));
            } else if (line.startsWith("|")) {
                // Markdown 表格的分隔行（|---|:---:|）没有可读内容，直接跳过
                if (!isTableSeparator(line)) {
                    appendTableRow(document, line);
                }
            } else if (isThematicBreak(line)) {
                // 分隔线在 Word 中忽略
                continue;
            } else if (isBulletLine(line)) {
                appendBullet(document, line.substring(2).trim());
            } else if (ORDERED_LIST_PATTERN.matcher(line).matches()) {
                appendBullet(document, line.replaceFirst("^\\d+[.)]\\s+", "").trim());
            } else {
                appendParagraph(document, line);
            }
        }
    }

    /**
     * 页面设置：A4、上下 2cm、左右 2.2cm，避免 Word 使用默认 Letter 纸张
     */
    private static void setupPage(XWPFDocument document) {
        CTBody body = document.getDocument().getBody();
        CTSectPr section = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();

        CTPageSz pageSize = section.addNewPgSz();
        pageSize.setW(BigInteger.valueOf(PAGE_WIDTH_TWIPS));
        pageSize.setH(BigInteger.valueOf(PAGE_HEIGHT_TWIPS));

        CTPageMar margin = section.addNewPgMar();
        margin.setTop(BigInteger.valueOf(PAGE_MARGIN_VERTICAL_TWIPS));
        margin.setBottom(BigInteger.valueOf(PAGE_MARGIN_VERTICAL_TWIPS));
        margin.setLeft(BigInteger.valueOf(PAGE_MARGIN_HORIZONTAL_TWIPS));
        margin.setRight(BigInteger.valueOf(PAGE_MARGIN_HORIZONTAL_TWIPS));
        margin.setHeader(BigInteger.valueOf(PAGE_HEADER_FOOTER_TWIPS));
        margin.setFooter(BigInteger.valueOf(PAGE_HEADER_FOOTER_TWIPS));
        margin.setGutter(BigInteger.ZERO);
    }

    /**
     * 初始化文档样式：正文默认字体 + Heading 1/2/3
     */
    private static void initStyles(XWPFDocument document) {
        XWPFStyles styles = document.createStyles();
        // 文档默认字体，避免未显式设置 run 的文字（如 Word 自动编号）回退到宋体
        styles.setDefaultFonts(defaultFonts());

        addNormalStyle(styles);
        for (int level = 1; level <= MAX_HEADING_LEVEL; level++) {
            addHeadingStyle(styles, level);
        }
    }

    /**
     * 统一的字体声明：ascii / hAnsi / eastAsia / cs 都指向微软雅黑
     */
    private static CTFonts defaultFonts() {
        CTFonts fonts = CTFonts.Factory.newInstance();
        fonts.setAscii(FONT_FAMILY);
        fonts.setHAnsi(FONT_FAMILY);
        fonts.setEastAsia(FONT_FAMILY);
        fonts.setCs(FONT_FAMILY);
        return fonts;
    }

    private static void addNormalStyle(XWPFStyles styles) {
        CTStyle ctStyle = CTStyle.Factory.newInstance();
        ctStyle.setType(STStyleType.PARAGRAPH);
        ctStyle.setDefault(true);
        ctStyle.setStyleId("Normal");
        ctStyle.addNewName().setVal("Normal");
        ctStyle.addNewQFormat();

        CTRPr runProperties = ctStyle.addNewRPr();
        applyStyleFont(runProperties, BODY_FONT_SIZE);

        styles.addStyle(new XWPFStyle(ctStyle, styles));
    }

    private static void addHeadingStyle(XWPFStyles styles, int level) {
        CTStyle ctStyle = CTStyle.Factory.newInstance();
        ctStyle.setType(STStyleType.PARAGRAPH);
        ctStyle.setStyleId("Heading" + level);
        ctStyle.addNewName().setVal("heading " + level);
        ctStyle.addNewBasedOn().setVal("Normal");
        ctStyle.addNewNext().setVal("Normal");
        ctStyle.addNewQFormat();

        CTPPrGeneral paragraphProperties = ctStyle.addNewPPr();
        paragraphProperties.addNewKeepNext().setVal(true);
        CTSpacing spacing = paragraphProperties.addNewSpacing();
        spacing.setBefore(BigInteger.valueOf(HEADING_SPACING_BEFORE));
        spacing.setAfter(BigInteger.valueOf(HEADING_SPACING_AFTER));
        // w:outlineLvl 从 0 开始，决定 Word 导航窗格的层级
        paragraphProperties.addNewOutlineLvl().setVal(BigInteger.valueOf(level - 1L));

        CTRPr runProperties = ctStyle.addNewRPr();
        applyStyleFont(runProperties, HEADING_FONT_SIZES[level - 1]);
        runProperties.addNewB().setVal(true);

        styles.addStyle(new XWPFStyle(ctStyle, styles));
    }

    /**
     * 给样式定义写入字体与字号（字号单位为半磅）
     */
    private static void applyStyleFont(CTRPr runProperties, int fontSize) {
        runProperties.addNewSz().setVal(BigInteger.valueOf(fontSize * 2L));
        runProperties.addNewSzCs().setVal(BigInteger.valueOf(fontSize * 2L));

        CTFonts fonts = runProperties.addNewRFonts();
        fonts.setAscii(FONT_FAMILY);
        fonts.setHAnsi(FONT_FAMILY);
        fonts.setEastAsia(FONT_FAMILY);
        fonts.setCs(FONT_FAMILY);
    }

    /**
     * 标题：设置 Heading 样式，并对 run 再做一次显式格式化（样式缺失时也能看出层级）
     */
    private static void appendHeading(XWPFDocument document, String line, int headingMarker) {
        int level = Math.min(headingMarker, MAX_HEADING_LEVEL);
        String text = line.substring(headingMarker).trim();
        // 去掉 ATX 标题结尾可选的 #（如 "## 标题 ##"）
        text = text.replaceAll("#+\\s*$", "").trim();
        if (text.isEmpty()) {
            return;
        }

        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading" + level);
        paragraph.setSpacingBefore(HEADING_SPACING_BEFORE);
        paragraph.setSpacingAfter(HEADING_SPACING_AFTER);
        appendInlineRuns(paragraph, text, true, false, HEADING_FONT_SIZES[level - 1]);
    }

    /**
     * 引用：缩进 + 斜体小字
     */
    private static void appendQuote(XWPFDocument document, String text) {
        if (text.isEmpty()) {
            return;
        }
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(LIST_INDENT);
        paragraph.setSpacingAfter(PARAGRAPH_SPACING_AFTER);
        appendInlineRuns(paragraph, text, false, true, QUOTE_FONT_SIZE);
    }

    /**
     * 列表项：统一用 • 前缀（Word 列表段落样式留待后续扩展）
     */
    private static void appendBullet(XWPFDocument document, String text) {
        if (text.isEmpty()) {
            return;
        }
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(LIST_INDENT);
        paragraph.setSpacingAfter(60);
        appendInlineRuns(paragraph, "• " + text, false, false, BODY_FONT_SIZE);
    }

    /**
     * 表格行：按 | 拆分后拼成一段文字，不做真实 Word 表格
     */
    private static void appendTableRow(XWPFDocument document, String line) {
        String content = line;
        if (content.startsWith("|")) {
            content = content.substring(1);
        }
        if (content.endsWith("|")) {
            content = content.substring(0, content.length() - 1);
        }

        List<String> cells = new ArrayList<>();
        for (String cell : content.split("\\|", -1)) {
            String value = cell.trim();
            if (!value.isEmpty()) {
                cells.add(value);
            }
        }
        if (cells.isEmpty()) {
            return;
        }

        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(60);
        appendInlineRuns(paragraph, String.join(" | ", cells), false, false, TABLE_FONT_SIZE);
    }

    /**
     * 普通段落
     */
    private static void appendParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(PARAGRAPH_SPACING_AFTER);
        appendInlineRuns(paragraph, text, false, false, BODY_FONT_SIZE);
    }

    /**
     * 解析行内 Markdown（**粗体**、`代码`、[链接](url)）并拆成多个 run
     */
    private static void appendInlineRuns(
            XWPFParagraph paragraph,
            String text,
            boolean bold,
            boolean italic,
            int fontSize
    ) {
        String source = LINK_PATTERN.matcher(text).replaceAll("$1");
        String[] parts = source.split("\\*\\*", -1);

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].replace("`", "");
            if (part.isEmpty()) {
                continue;
            }
            XWPFRun run = paragraph.createRun();
            run.setText(part);
            applyRunFont(run, bold || i % 2 == 1, italic, fontSize);
        }
    }

    /**
     * 应用 run 字体：中英文字体都指向微软雅黑
     */
    private static void applyRunFont(XWPFRun run, boolean bold, boolean italic, int fontSize) {
        run.setFontSize(fontSize);
        run.setFontFamily(FONT_FAMILY);
        run.setFontFamily(FONT_FAMILY, XWPFRun.FontCharRange.ascii);
        run.setFontFamily(FONT_FAMILY, XWPFRun.FontCharRange.hAnsi);
        run.setFontFamily(FONT_FAMILY, XWPFRun.FontCharRange.eastAsia);
        if (bold) {
            run.setBold(true);
        }
        if (italic) {
            run.setItalic(true);
        }
    }

    /**
     * 判断是否为标题行，返回 # 的数量；不是标题返回 0
     */
    private static int countHeadingMarker(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }
        if (count == 0 || count >= line.length() || !Character.isWhitespace(line.charAt(count))) {
            return 0;
        }
        return count;
    }

    /**
     * 去掉引用前缀 >（支持 >> 嵌套）
     */
    private static String stripQuoteMarker(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == '>') {
            index++;
        }
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return line.substring(index).trim();
    }

    private static boolean isBulletLine(String line) {
        if (line.length() < 2) {
            return false;
        }
        char marker = line.charAt(0);
        return (marker == '-' || marker == '*' || marker == '+')
                && Character.isWhitespace(line.charAt(1));
    }

    /**
     * 表格分隔行，例如 |---|:---:|
     */
    private static boolean isTableSeparator(String line) {
        String content = line.replace('|', ' ')
                .replace(':', ' ')
                .replace('-', ' ')
                .trim();
        return content.isEmpty() && line.indexOf('-') >= 0;
    }

    /**
     * 段落分隔线，例如 --- / *** / ___
     */
    private static boolean isThematicBreak(String line) {
        String compact = line.replace(" ", "");
        if (compact.length() < 3) {
            return false;
        }
        char first = compact.charAt(0);
        if (first != '-' && first != '*' && first != '_') {
            return false;
        }
        for (int i = 1; i < compact.length(); i++) {
            if (compact.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }
}
