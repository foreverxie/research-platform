package com.research.platform.common.dto;

import lombok.Data;

/**
 * 分页查询基类
 *
 * <p>前端统一使用 {@code page}/{@code size}（见 README 的分页约定），
 * 同时保留 {@code pageNum}/{@code pageSize} 作为兼容别名，
 * 避免历史调用方与第三方脚本失效。</p>
 */
@Data
public class PageQueryDTO {

    /**
     * 页码，从 1 开始
     */
    private Integer page = 1;

    /**
     * 每页条数
     */
    private Integer size = 10;

    /**
     * 规范化页码
     */
    public long pageNo() {
        return page == null || page < 1 ? 1 : page;
    }

    /**
     * 规范化每页条数（1-100）
     */
    public long pageSize() {
        if (size == null || size < 1) {
            return 10;
        }
        return Math.min(size, 100);
    }

    /**
     * 兼容历史参数名 pageNum
     */
    public Integer getPageNum() {
        return page;
    }

    public void setPageNum(Integer pageNum) {
        this.page = pageNum;
    }

    /**
     * 兼容历史参数名 pageSize
     */
    public Integer getPageSize() {
        return size;
    }

    public void setPageSize(Integer pageSize) {
        this.size = pageSize;
    }
}
