package com.atguigu.gulimall.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 工具返回值的统一处理。
 *
 * <p>业务模块统一返回 R（code/msg/data/page），工具只要把 data/page 转成 JSON 给模型。
 * 结果过长会截断，防止一次工具调用把上下文窗口塞满。
 */
public final class ToolResult {

    private static final int MAX_LENGTH = 6000;

    /**
     * 工具类，禁止实例化。
     */
    private ToolResult() {
    }

    /**
     * 把业务接口返回的统一响应转换成模型可读的文本，优先取 data、page 或 coupons。
     *
     * @param response 业务接口返回的统一响应，包含 code、msg、data、page 等字段
     * @param mapper   JSON 序列化器
     * @return 转换后的 JSON 文本或中文错误提示，过长时会被截断
     */
    public static String from(Map<String, Object> response, ObjectMapper mapper) {
        // 1. 空响应
        if (response == null) {
            return "查询失败：服务返回为空";
        }

        // 2. code 非 0 说明业务失败，直接把 msg 交给模型
        Object code = response.get("code");
        if (code != null && !"0".equals(String.valueOf(code))) {
            Object msg = response.get("msg");
            return "查询失败：" + (msg == null ? code : msg);
        }

        // 3. 优先取 data，其次 page/coupons，最后返回整个响应
        if (response.containsKey("data")) {
            return json(response.get("data"), mapper);
        }
        if (response.containsKey("page")) {
            return json(response.get("page"), mapper);
        }
        if (response.containsKey("coupons")) {
            return json(response.get("coupons"), mapper);
        }
        return json(response, mapper);
    }

    /**
     * 原样返回外部接口已生成的 JSON 或文本，仅做空值和长度处理。
     *
     * @param text 外部接口返回的文本
     * @return 原文本或截断后的文本；text 为空时返回“没有查到数据”
     */
    public static String raw(String text) {
        // 1. 外部 API 已经返回 JSON/文本时原样给，只做长度截断
        if (text == null) {
            return "没有查到数据";
        }
        if (text.length() > MAX_LENGTH) {
            return text.substring(0, MAX_LENGTH) + "...(已截断)";
        }
        return text;
    }

    /**
     * 把任意对象序列化成 JSON 文本，并限制最大长度。
     *
     * @param value  待序列化的对象
     * @param mapper JSON 序列化器
     * @return 序列化后的 JSON 文本；value 为空时返回“没有查到数据”
     */
    public static String json(Object value, ObjectMapper mapper) {
        // 1. 空值给一句人话，模型不会把 null 当数据
        if (value == null) {
            return "没有查到数据";
        }

        try {
            // 2. 序列化并按上限截断
            String json = mapper.writeValueAsString(value);
            if (json.length() > MAX_LENGTH) {
                return json.substring(0, MAX_LENGTH) + "...(已截断)";
            }
            return json;
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}
