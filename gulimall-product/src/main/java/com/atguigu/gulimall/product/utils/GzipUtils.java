package com.atguigu.gulimall.product.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * @ClassName: GzipUtils
 * @Package: com.atguigu.gulimall.product.utils
 * @Description: JSON 缓存值压缩工具
 * @Author: 刘家旭
 * @Create: 2026/9/22
 * @Version: v1.0
 */
public final class GzipUtils {

    /** GZIP 流的固定魔数：任何 GZIP 数据的前两个字节必然是 0x1f 0x8b */
    private static final byte GZIP_MAGIC_FIRST = (byte) 0x1f;

    private GzipUtils() {
    }

    /**
     * 压缩为字节数组。
     * <p>
     * 直接返回 byte[] 而不是 Base64 字符串：配合 RedisTemplate&lt;String, byte[]&gt; 可以
     * 把二进制原样写进 Redis，省掉 Base64 编码带来的约 33% 体积膨胀
     * （实测 19.6KB 的 Base64 结果，原始 GZIP 只有 14.7KB）。
     *
     * @param text 原文（未压缩的 JSON）
     * @return GZIP 压缩后的字节；入参为空时返回 null
     */
    public static byte[] compress(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, text.length() / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("GZIP 压缩失败", e);
        }
        return bos.toByteArray();
    }

    /**
     * 解压回原文。
     *
     * @param data {@link #compress} 的产物
     * @return 原始 JSON 字符串；入参为空时返回 null
     */
    public static String decompress(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        // 存量兼容（一）：上线前写入的明文 JSON。JSON 一定以 { 开头，
        // 而 GZIP 的首字节是 0x1f，据此区分。等 Redis 里的旧值随 1 天 TTL 自然过期后可删。
        if (data[0] == '{') {
            return new String(data, StandardCharsets.UTF_8);
        }

        byte[] gzipBytes = data;
        // 存量兼容（二）：中间版本用 Base64 存过一版。Base64 的字符集是 A-Za-z0-9+/=，
        // 不会出现 0x1f，所以首字节不是 GZIP 魔数就按 Base64 解一次。同样可随 TTL 过期后删除。
        if (data[0] != GZIP_MAGIC_FIRST) {
            gzipBytes = Base64.getDecoder().decode(data);
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(gzipBytes));
             ByteArrayOutputStream bos = new ByteArrayOutputStream(gzipBytes.length * 5)) {
            gzip.transferTo(bos);
            return bos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("GZIP 解压失败", e);
        }
    }
}
