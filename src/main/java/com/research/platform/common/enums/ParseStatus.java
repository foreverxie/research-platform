package com.research.platform.common.enums;

/**
 * 文献解析状态（对应 document.parse_status 与 parse_record.status）
 *
 * <p>document.parse_status 表示“最近一次解析尝试”的状态；
 * parse_record.status 表示“某一次解析任务”的状态；
 * 文献当前是否可检索应以 document.current_parse_record_id 为准。</p>
 */
public enum ParseStatus {

    /** 待解析 */
    PENDING,

    /** 解析中 */
    PROCESSING,

    /** 解析成功 */
    SUCCESS,

    /** 解析失败 */
    FAILED
}
