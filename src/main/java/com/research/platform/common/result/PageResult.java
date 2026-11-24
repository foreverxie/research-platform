package com.research.platform.common.result;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {

    private Long total;         // 数据总数

    private Long current;       // 当前页码

    private Long size;          // 每页条数

    private List<T> records;    // 数据列表

    public PageResult() {
    }

    public PageResult(
            Long total,
            Long current,
            Long size,
            List<T> records
    ) {
        this.total = total;
        this.current = current;
        this.size = size;
        this.records = records;
    }
}