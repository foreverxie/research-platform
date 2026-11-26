package com.research.platform.modules.model.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.infrastructure.ai.AiServiceClient;
import com.research.platform.modules.model.convert.ModelConfigConvert;
import com.research.platform.modules.model.dto.ModelConfigUpdateDTO;
import com.research.platform.modules.model.entity.ModelConfig;
import com.research.platform.modules.model.mapper.ModelConfigMapper;
import com.research.platform.modules.model.service.IModelConfigService;
import com.research.platform.modules.model.vo.ModelConfigVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置服务实现
 */
@Service
public class ModelConfigServiceImpl implements IModelConfigService {

    private final ModelConfigMapper modelConfigMapper;
    private final AiServiceClient aiServiceClient;

    public ModelConfigServiceImpl(ModelConfigMapper modelConfigMapper, AiServiceClient aiServiceClient) {
        this.modelConfigMapper = modelConfigMapper;
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public List<ModelConfigVO> listConfigs() {
        List<ModelConfig> configs = modelConfigMapper.selectList(new QueryWrapper<ModelConfig>()
                .orderByAsc("id"));

        return configs.stream().map(ModelConfigConvert::toVO).toList();
    }

    /**
     * 更新模型配置
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelConfigVO updateConfig(Long id, ModelConfigUpdateDTO dto) {
        ModelConfig config = modelConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException(404, "模型配置不存在");
        }

        if (dto.getModelName() != null) {
            String modelName = dto.getModelName().trim();
            if (modelName.isEmpty()) {
                throw new BusinessException(400, "模型名称不能为空");
            }
            config.setModelName(modelName);
        }
        if (dto.getProvider() != null) {
            config.setProvider(dto.getProvider().trim());
        }
        if (dto.getApiEndpoint() != null) {
            config.setApiEndpoint(dto.getApiEndpoint().trim());
        }
        if (dto.getApiKeyEnvName() != null) {
            config.setApiKeyEnvName(dto.getApiKeyEnvName().trim());
        }
        if (dto.getDimension() != null) {
            config.setDimension(dto.getDimension());
        }
        if (dto.getStatus() != null) {
            config.setStatus(dto.getStatus());
        }

        // 设为默认时，同 modelType 的其他配置取消默认
        if (dto.getIsDefault() != null) {
            if (dto.getIsDefault() == 1) {
                modelConfigMapper.update(null, new LambdaUpdateWrapper<ModelConfig>()
                        .eq(ModelConfig::getModelType, config.getModelType())
                        .ne(ModelConfig::getId, id)
                        .set(ModelConfig::getIsDefault, 0));
            }
            config.setIsDefault(dto.getIsDefault());
        }

        modelConfigMapper.updateById(config);

        return ModelConfigConvert.toVO(modelConfigMapper.selectById(id));
    }

    /**
     * 测试连通性
     */
    @Override
    public Map<String, Object> testConnection(Long id) {
        ModelConfig config = modelConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException(404, "模型配置不存在");
        }
        if (config.getStatus() == null || config.getStatus() == 0) {
            throw new BusinessException(400, "模型已停用，无法测试连通性");
        }

        long startedAt = System.currentTimeMillis();
        boolean ok = aiServiceClient.ping();
        long latencyMs = System.currentTimeMillis() - startedAt;

        String message = ok
                ? config.getModelName() + " 连通正常"
                : "无法连接 AI 服务（research.ai.enabled=false 或服务未启动）";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok);
        result.put("latencyMs", latencyMs);
        result.put("message", message);
        return result;
    }
}
