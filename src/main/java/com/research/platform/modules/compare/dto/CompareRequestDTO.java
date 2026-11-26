package com.research.platform.modules.compare.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 多篇文献对比请求
 */
@Data
public class CompareRequestDTO {

    /**
     * 参与对比的文献ID，2-6 篇
     */
    @NotEmpty(message = "请选择需要对比的文献")
    @Size(min = 2, max = 6, message = "请选择 2-6 篇文献进行对比")
    private List<Long> documentIds;
}
