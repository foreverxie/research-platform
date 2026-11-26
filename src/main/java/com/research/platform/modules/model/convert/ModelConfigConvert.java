package com.research.platform.modules.model.convert;

import com.research.platform.modules.model.entity.ModelConfig;
import com.research.platform.modules.model.vo.ModelConfigVO;

/**
 * 模型配置对象转换
 */
public final class ModelConfigConvert {

    private ModelConfigConvert() {
    }

    public static ModelConfigVO toVO(ModelConfig config) {
        if (config == null) {
            return null;
        }

        ModelConfigVO vo = new ModelConfigVO();
        vo.setId(config.getId());
        vo.setModelName(config.getModelName());
        vo.setModelType(config.getModelType());
        vo.setProvider(config.getProvider());
        vo.setApiEndpoint(config.getApiEndpoint());
        vo.setApiKeyEnvName(config.getApiKeyEnvName());
        vo.setDimension(config.getDimension());
        vo.setIsDefault(config.getIsDefault());
        vo.setStatus(config.getStatus());
        vo.setCreatedAt(config.getCreatedAt());
        return vo;
    }
}
