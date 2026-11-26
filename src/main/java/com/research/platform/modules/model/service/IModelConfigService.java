package com.research.platform.modules.model.service;

import com.research.platform.modules.model.dto.ModelConfigUpdateDTO;
import com.research.platform.modules.model.vo.ModelConfigVO;

import java.util.List;
import java.util.Map;

/**
 * AI 模型配置服务
 *
 * <p>模型配置是系统级配置：登录用户均可读取与维护，但只保存密钥的
 * 环境变量名，密钥本身通过环境变量注入。</p>
 */
public interface IModelConfigService {

    /**
     * 查询全部模型配置
     */
    List<ModelConfigVO> listConfigs();

    /**
     * 更新模型配置；设为默认时同类型其他配置自动取消默认
     */
    ModelConfigVO updateConfig(Long id, ModelConfigUpdateDTO dto);

    /**
     * 测试模型连通性
     *
     * @return ok / latencyMs / message
     */
    Map<String, Object> testConnection(Long id);
}
