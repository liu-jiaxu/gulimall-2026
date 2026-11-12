package com.atguigu.gulimall.agent.settings.mapper;

import com.atguigu.gulimall.agent.settings.entity.AiUserSetting;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * `ai_user_setting` 表的 MyBatis-Plus Mapper。
 *
 * <p>一个身份（USER/ADMIN + id）只有一行设置，按 principal_type/principal_id 查。
 */
@Mapper
public interface AiUserSettingMapper extends BaseMapper<AiUserSetting> {
}
