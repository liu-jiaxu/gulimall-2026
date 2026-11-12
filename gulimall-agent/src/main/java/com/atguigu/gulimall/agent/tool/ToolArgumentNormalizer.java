package com.atguigu.gulimall.agent.tool;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 工具参数兼容层：把各家模型常见的非严格 JSON 修成 Jackson 能解析的 JSON。
 *
 * <p>不同模型的 function calling 输出并不完全一样：有的带 Markdown 代码块，
 * 有的在最后一个字段后多一个逗号，有的用中文逗号/冒号，有的把 JSON 再包一层字符串。
 * 这些输入直接交给 LangChain4J 1.0.1 会抛 JsonParseException，
 * 流式回调还可能把异常吞掉，最终前端一直等待。
 *
 * <p>处理顺序：去代码块 → 去尾随逗号 → 宽松 JSON 解析 → 仍失败时修正常见标点 → 再解析。
 * 实在无法修复时抛 IllegalArgumentException，由工具执行器转成模型可读的错误结果。
 */
public final class ToolArgumentNormalizer {

    /** 默认序列化器，用于把修复后的参数重新输出成标准 JSON */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 宽松解析器：允许单引号、无引号字段名、尾随逗号、注释和反斜杠转义 */
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    private ToolArgumentNormalizer() {
    }

    /**
     * 把模型返回的工具参数修成标准 JSON。
     *
     * @param raw 模型原始参数
     * @return 标准 JSON 字符串；空参数返回 {}
     * @throws IllegalArgumentException 无法修复时抛出
     */
    public static String normalize(String raw) {
        // 1. 模型没有传参数时按空对象处理，避免工具方法收到 null
        if (raw == null || raw.isBlank()) {
            return "{}";
        }

        // 2. 进入真正的修复流程
        return normalize(raw.trim(), 0);
    }

    /**
     * 递归处理参数，最多拆一层“JSON 字符串里再包 JSON”。
     *
     * @param raw   原始参数
     * @param depth 已递归层数
     * @return 标准 JSON
     */
    private static String normalize(String raw, int depth) {
        if (depth > 2) {
            throw new IllegalArgumentException("工具参数嵌套层数过深");
        }

        // 1. 先只去 Markdown 代码块，尽量保留原始字符串内容
        String text = stripCodeFence(raw);

        // 2. 先用宽松解析器尝试；成功则统一输出标准 JSON
        try {
            return toJson(LENIENT.readValue(text, Object.class), depth);
        } catch (Exception ignored) {
            // 3. 解析失败后再去尾随逗号，避免误伤字符串内部的逗号
        }

        String repaired = removeTrailingCommas(text);
        try {
            return toJson(LENIENT.readValue(repaired, Object.class), depth);
        } catch (Exception ignored) {
            // 4. 仍失败时继续修正常见中文标点
        }

        repaired = removeTrailingCommas(normalizePunctuation(repaired));
        try {
            return toJson(LENIENT.readValue(repaired, Object.class), depth);
        } catch (Exception ex) {
            throw new IllegalArgumentException("工具参数不是合法 JSON", ex);
        }
    }

    /**
     * 把解析结果重新序列化；如果模型把 JSON 放在字符串里，则拆开再解析。
     *
     * @param value 宽松解析后的对象
     * @param depth 已递归层数
     * @return 标准 JSON
     */
    private static String toJson(Object value, int depth) throws Exception {
        // 1. 双重编码：先解析出来的是一个字符串，里面才是真正的参数
        if (value instanceof String stringValue) {
            return normalize(stringValue.trim(), depth + 1);
        }

        // 2. 工具参数必须是对象；数组、数字、布尔值都不合法
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("工具参数不是 JSON 对象");
        }
        return MAPPER.writeValueAsString(value);
    }

    /**
     * 去掉 Markdown 代码块标记。
     *
     * @param text 原始文本
     * @return 去掉代码块后的文本
     */
    private static String stripCodeFence(String text) {
        // 1. 只处理以 ``` 开头的参数，正常 JSON 不受影响
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        String body = text.substring(firstLineEnd + 1);
        // 2. 去掉结尾的 ```，允许后面有空白
        return body.replaceFirst("(?s)\\s*```\\s*$", "");
    }

    /**
     * 去掉对象或数组最后一个元素后面的逗号。
     *
     * @param text 原始文本
     * @return 去尾随逗号后的文本
     */
    private static String removeTrailingCommas(String text) {
        // 1. 有些模型会输出 {"a":1,} 或 [1,2,]
        String result = text;
        for (int i = 0; i < 3; i++) {
            String next = result.replaceAll(",\\s*([}\\]])", "$1");
            if (next.equals(result)) {
                break;
            }
            result = next;
        }
        return result;
    }

    /**
     * 把字符串外的中文逗号、中文冒号和中文引号修成 JSON 符号。
     *
     * <p>字符串内部的中文标点不替换，避免破坏商品名、地址等真实内容。
     *
     * @param text 原始文本
     * @return 修正后的文本
     */
    private static String normalizePunctuation(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        char quote = 0;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // 1. 字符串内部：只处理转义和结束引号，其它中文标点原样保留
            if (inString) {
                if (escaped) {
                    out.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    out.append(c);
                    escaped = true;
                } else if (c == quote) {
                    inString = false;
                    out.append(c);
                } else {
                    out.append(c);
                }
                continue;
            }

            // 2. 字符串外部：中文引号当字符串边界，中文逗号/冒号转成 JSON 符号
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                out.append(c);
            } else if (c == '“' || c == '‘') {
                inString = true;
                quote = c == '“' ? '"' : '\'';
                out.append(quote);
            } else if (c == '”' || c == '’') {
                inString = false;
                out.append(quote == 0 ? '"' : quote);
            } else if (c == '，') {
                out.append(',');
            } else if (c == '：') {
                out.append(':');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
