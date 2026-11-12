package com.atguigu.gulimall.agent.settings.mapper;

import com.atguigu.gulimall.agent.settings.entity.AiCustomModel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * `ai_custom_model` 表的 MyBatis-Plus Mapper。
 *
 * <p>只做单表 CRUD，复杂查询在 SettingsService 里用 LambdaQueryWrapper 拼。
 */
@Mapper
public interface AiCustomModelMapper extends BaseMapper<AiCustomModel> {
}
