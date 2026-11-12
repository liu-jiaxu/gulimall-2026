package com.atguigu.gulimall.agent.image;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 上传图片的元数据，存 Redis 3 天。
 *
 * <p>聊天请求只带 imageId，服务端凭元数据找回 MinIO 对象和归属人；
 * 不能只信前端传来的 URL，否则可以拿别人的图片 id 去“看图”。
 */
@Service
@RequiredArgsConstructor
public class ImageMetadataService {

    private static final String PREFIX = "gulimall:ai:img:";

    private final StringRedisTemplate redisTemplate;
    private final AiProperties properties;

    /**
     * 将图片归属人、对象地址、MIME 与大小写入 Redis，并设置过期时间。
     *
     * @param principal 当前登录用户
     * @param imageId   图片唯一标识
     * @param objectKey 图片在 MinIO 中的对象路径
     * @param url       图片的公开访问地址
     * @param mime      图片的 MIME 类型
     * @param size      图片字节大小
     */
    public void save(Principal principal, String imageId, String objectKey,
                     String url, String mime, long size) {
        // 1. 记录归属人、对象地址、MIME、大小
        String key = PREFIX + imageId;
        redisTemplate.opsForHash().putAll(key, Map.of(
                "ownerType", principal.type().name(),
                "ownerId", String.valueOf(principal.id()),
                "objectKey", objectKey,
                "url", url,
                "mime", mime,
                "size", String.valueOf(size)
        ));
        redisTemplate.expire(key, properties.getConversation().getTtl());
    }

    /**
     * 按图片 id 读取元数据，并校验图片归属当前用户。
     *
     * @param principal 当前登录用户
     * @param imageId   图片唯一标识
     * @return 图片的元数据
     */
    public ImageMeta get(Principal principal, String imageId) {
        // 1. 找不到说明过期或没上传
        if (imageId == null || imageId.isBlank()) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID, "图片 id 不能为空");
        }
        Map<Object, Object> meta = redisTemplate.opsForHash().entries(PREFIX + imageId);
        if (meta.isEmpty()) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID, "图片不存在或已过期，请重新上传");
        }

        // 2. 只能使用自己上传的图片
        if (!principal.type().name().equals(String.valueOf(meta.get("ownerType")))
                || !String.valueOf(principal.id()).equals(String.valueOf(meta.get("ownerId")))) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID, "无权使用该图片");
        }

        // 3. 返回给业务用的元数据
        return new ImageMeta(
                imageId,
                String.valueOf(meta.get("objectKey")),
                String.valueOf(meta.get("url")),
                String.valueOf(meta.get("mime")),
                Long.parseLong(String.valueOf(meta.get("size")))
        );
    }

    /**
     * 上传图片的元数据。
     *
     * @param imageId   图片 id
     * @param objectKey MinIO 对象路径
     * @param url       可访问地址
     * @param mime      图片 MIME 类型
     * @param size      图片字节数
     */
    public record ImageMeta(String imageId, String objectKey, String url, String mime, long size) {
    }
}
