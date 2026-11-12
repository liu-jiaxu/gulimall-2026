package com.atguigu.gulimall.agent.support;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.auth.PrincipalType;
import com.atguigu.gulimall.agent.chat.memory.ConversationService;
import com.atguigu.gulimall.agent.settings.entity.AiCustomModel;
import com.atguigu.gulimall.agent.settings.entity.AiUserSetting;
import com.atguigu.gulimall.agent.settings.mapper.AiCustomModelMapper;
import com.atguigu.gulimall.agent.settings.mapper.AiUserSettingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 真实环境集成测试基类。
 *
 * <p>连接真实 MySQL、Redis、MinIO、Milvus、Nacos 和外部模型服务；
 * 只使用 Spring 容器和真实 HTTP，不替换任何业务 Bean。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
public abstract class AgentIntegrationTestSupport {

    protected static final long TEST_USER_ID = 990001L;
    protected static final long TEST_ADMIN_ID = 990002L;

    protected static final Principal TEST_USER =
            new Principal(PrincipalType.USER, TEST_USER_ID, "it_user", "IT User");
    protected static final Principal TEST_ADMIN =
            new Principal(PrincipalType.ADMIN, TEST_ADMIN_ID, "it_admin", "IT Admin");

    private static final String USER_COOKIE = "AGENT_JWT_USER";
    private static final String ADMIN_COOKIE = "AGENT_JWT_ADMIN";

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected ConversationService conversationService;

    @Autowired
    protected AiUserSettingMapper settingMapper;

    @Autowired
    protected AiCustomModelMapper customModelMapper;

    @Autowired
    protected MinioClient minioClient;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Value("${MINIO_BUCKET:gulimall}")
    protected String minioBucket;

    private final Set<String> trackedConversations = ConcurrentHashMap.newKeySet();
    private final Set<String> trackedImages = ConcurrentHashMap.newKeySet();
    private final Set<String> trackedTasks = ConcurrentHashMap.newKeySet();

    private String userToken;
    private String adminToken;

    @DynamicPropertySource
    static void jwtKeyPaths(DynamicPropertyRegistry registry) {
        Path root = projectRoot();
        registry.add("gulimall.agent.jwt.user-public-key-path",
                () -> root.resolve("jwt/user/jwt_public.pem").toString());
        registry.add("gulimall.agent.jwt.admin-public-key-path",
                () -> root.resolve("jwt/admin/jwt_public.pem").toString());
    }

    /**
     * 从测试工作目录向上找到仓库根目录，用于定位 jwt 密钥文件。
     *
     * @return 仓库根目录
     */
    protected static Path projectRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.exists(dir.resolve("jwt/user/jwt_public.pem"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Paths.get("D:/SoftwareInstallation/gulimall");
    }

    /**
     * 生成用户端 JWT，使用仓库中的真实私钥。
     *
     * @return 用户端 JWT
     */
    protected synchronized String userToken() {
        if (userToken == null) {
            userToken = signToken(TEST_USER_ID, "it_user", "user",
                    "user-2026-01", "gulimall-auth-server",
                    projectRoot().resolve("jwt/user/jwt_private.pem"));
        }
        return userToken;
    }

    /**
     * 生成管理端 JWT，使用仓库中的真实私钥。
     *
     * @return 管理端 JWT
     */
    protected synchronized String adminToken() {
        if (adminToken == null) {
            adminToken = signToken(TEST_ADMIN_ID, "it_admin", "admin",
                    "admin-2026-01", "renren-fast",
                    projectRoot().resolve("jwt/admin/jwt_private.pem"));
        }
        return adminToken;
    }

    /**
     * 使用指定私钥签发 JWT。
     *
     * @param id      主体 id
     * @param name    用户名
     * @param role    角色
     * @param kid     密钥编号
     * @param issuer  签发方
     * @param keyPath 私钥文件
     * @return JWT 字符串
     */
    protected String signToken(Long id, String name, String role, String kid,
                               String issuer, Path keyPath) {
        try {
            // 1. 读取 PEM 并去掉头尾和空白
            String pem = Files.readString(keyPath, StandardCharsets.UTF_8)
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");

            // 2. 按 PKCS#8 生成私钥
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));

            // 3. 组装与签发服务一致的 Payload
            Instant now = Instant.now();
            return Jwts.builder()
                    .header().keyId(kid).and()
                    .issuer(issuer)
                    .audience().add("gulimall-agent").and()
                    .subject(String.valueOf(id))
                    .claim("username", name)
                    .claim("role", role)
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plusSeconds(1800)))
                    .id(UUID.randomUUID().toString())
                    .signWith(key, Jwts.SIG.RS256)
                    .compact();
        } catch (Exception ex) {
            throw new IllegalStateException("签发测试 JWT 失败：" + keyPath, ex);
        }
    }

    /**
     * 构造用户端请求头。
     *
     * @return 带 AGENT_JWT_USER Cookie 的请求头
     */
    protected HttpHeaders userHeaders() {
        return headers(USER_COOKIE, userToken());
    }

    /**
     * 构造管理端请求头。
     *
     * @return 带 AGENT_JWT_ADMIN Cookie 的请求头
     */
    protected HttpHeaders adminHeaders() {
        return headers(ADMIN_COOKIE, adminToken());
    }

    /**
     * 构造未登录请求头。
     *
     * @return 不带 JWT Cookie 的请求头
     */
    protected HttpHeaders anonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * 构造指定 Cookie 的请求头。
     *
     * @param cookieName Cookie 名
     * @param token      JWT
     * @return 请求头
     */
    protected HttpHeaders headers(String cookieName, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookieName + "=" + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * 拼接本测试进程内的 agent 地址。
     *
     * @param path 接口路径
     * @return 完整 URL
     */
    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * 发送 GET 请求。
     *
     * @param path    接口路径
     * @param headers 请求头
     * @return 响应体
     */
    protected Map<String, Object> get(String path, HttpHeaders headers) {
        return exchange(path, HttpMethod.GET, null, headers, Map.class);
    }

    /**
     * 发送 POST JSON 请求。
     *
     * @param path    接口路径
     * @param body    请求体
     * @param headers 请求头
     * @return 响应体
     */
    protected Map<String, Object> post(String path, Object body, HttpHeaders headers) {
        return exchange(path, HttpMethod.POST, body, headers, Map.class);
    }

    /**
     * 发送 PUT JSON 请求。
     *
     * @param path    接口路径
     * @param body    请求体
     * @param headers 请求头
     * @return 响应体
     */
    protected Map<String, Object> put(String path, Object body, HttpHeaders headers) {
        return exchange(path, HttpMethod.PUT, body, headers, Map.class);
    }

    /**
     * 发送 DELETE 请求。
     *
     * @param path    接口路径
     * @param headers 请求头
     * @return 响应体
     */
    protected Map<String, Object> delete(String path, HttpHeaders headers) {
        return exchange(path, HttpMethod.DELETE, null, headers, Map.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T exchange(String path, HttpMethod method, Object body,
                           HttpHeaders headers, Class<T> type) {
        ResponseEntity<T> response = rest.exchange(url(path), method,
                new HttpEntity<>(body, headers), type);
        assertNotNull(response.getBody(), "接口没有返回响应体：" + path);
        return response.getBody();
    }

    /**
     * 上传 multipart 图片。
     *
     * @param bytes       文件字节
     * @param filename    文件名
     * @param headers     请求头，会补充 multipart 类型
     * @return 响应体
     */
    protected Map<String, Object> upload(byte[] bytes, String filename, HttpHeaders headers) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        HttpHeaders multipartHeaders = new HttpHeaders();
        multipartHeaders.putAll(headers);
        multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<Map> response = rest.exchange(url("/agent/images"), HttpMethod.POST,
                new HttpEntity<>(body, multipartHeaders), Map.class);
        assertNotNull(response.getBody(), "图片上传没有返回响应体");
        return response.getBody();
    }

    /**
     * 读取统一响应中的 data。
     *
     * @param response 统一响应
     * @return data 对象
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> data(Map<String, Object> response) {
        Object data = response.get("data");
        assertNotNull(data, "响应中没有 data");
        return (Map<String, Object>) data;
    }

    /**
     * 读取统一响应中的 list。
     *
     * @param response 统一响应
     * @return list
     */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> list(Map<String, Object> response) {
        Object list = response.get("list");
        assertNotNull(list, "响应中没有 list");
        return (List<Map<String, Object>>) list;
    }

    /**
     * 记录本测试创建的会话，测试结束统一删除。
     *
     * @param conversationId 会话 id
     */
    protected void trackConversation(String conversationId) {
        if (conversationId != null) {
            trackedConversations.add(conversationId);
        }
    }

    /**
     * 记录本测试上传的图片，测试结束统一删除。
     *
     * @param imageId 图片 id
     */
    protected void trackImage(String imageId) {
        if (imageId != null) {
            trackedImages.add(imageId);
        }
    }

    /**
     * 记录本测试提交的图片任务，测试结束统一删除。
     *
     * @param taskId 任务 id
     */
    protected void trackTask(String taskId) {
        if (taskId != null) {
            trackedTasks.add(taskId);
        }
    }

    /**
     * 打开真实 HTTP SSE 连接。
     *
     * @param path    接口路径
     * @param body    请求体
     * @param headers 请求头
     * @return SSE 会话
     * @throws Exception 连接失败
     */
    protected SseSession openSse(String path, Object body, HttpHeaders headers) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofMinutes(5))
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));

        headers.forEach((name, values) -> {
            // 1. Content-Type / Accept 已经由 SSE 请求固定，避免重复头
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)
                    || HttpHeaders.ACCEPT.equalsIgnoreCase(name)) {
                return;
            }
            // 2. 其它头（Cookie 等）原样带上
            values.forEach(value -> builder.header(name, value));
        });

        HttpResponse<InputStream> response = client.send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new SseSession(response.body());
    }

    /**
     * 清理本测试写入的 Redis、MySQL 和 MinIO 数据。
     */
    @AfterEach
    void cleanupTestData() {
        // 1. 删除会话相关的所有 Redis key 和索引
        for (String id : trackedConversations) {
            redis.delete(List.of(
                    "gulimall:ai:conv:meta:" + id,
                    "gulimall:ai:conv:msg:" + id,
                    "gulimall:ai:memory:" + id,
                    "gulimall:ai:stream:" + id,
                    "gulimall:ai:stop:" + id,
                    "gulimall:ai:lock:conv:" + id,
                    "gulimall:ai:imgtask:conv:" + id
            ));
            redis.opsForZSet().remove("gulimall:ai:conv:index:USER:" + TEST_USER_ID, id);
            redis.opsForZSet().remove("gulimall:ai:conv:index:ADMIN:" + TEST_ADMIN_ID, id);
        }
        redis.delete(List.of(
                "gulimall:ai:conv:index:USER:" + TEST_USER_ID,
                "gulimall:ai:conv:index:ADMIN:" + TEST_ADMIN_ID
        ));

        // 2. 删除图片元数据和图片任务
        for (String imageId : trackedImages) {
            redis.delete("gulimall:ai:img:" + imageId);
        }
        for (String taskId : trackedTasks) {
            redis.delete("gulimall:ai:imgtask:" + taskId);
        }

        // 3. 删除测试身份的 MySQL 设置和自定义模型
        settingMapper.delete(new LambdaQueryWrapper<AiUserSetting>()
                .eq(AiUserSetting::getPrincipalType, "USER")
                .eq(AiUserSetting::getPrincipalId, TEST_USER_ID));
        settingMapper.delete(new LambdaQueryWrapper<AiUserSetting>()
                .eq(AiUserSetting::getPrincipalType, "ADMIN")
                .eq(AiUserSetting::getPrincipalId, TEST_ADMIN_ID));
        customModelMapper.delete(new LambdaQueryWrapper<AiCustomModel>()
                .eq(AiCustomModel::getPrincipalType, "USER")
                .eq(AiCustomModel::getPrincipalId, TEST_USER_ID));
        customModelMapper.delete(new LambdaQueryWrapper<AiCustomModel>()
                .eq(AiCustomModel::getPrincipalType, "ADMIN")
                .eq(AiCustomModel::getPrincipalId, TEST_ADMIN_ID));

        // 4. 删除测试身份在 MinIO 中的上传图和生成图
        removeMinioPrefix("ai/upload/USER/" + TEST_USER_ID + "/");
        removeMinioPrefix("ai/upload/ADMIN/" + TEST_ADMIN_ID + "/");
        removeMinioPrefix("ai/generated/USER/" + TEST_USER_ID + "/");
        removeMinioPrefix("ai/generated/ADMIN/" + TEST_ADMIN_ID + "/");

        // 5. 兜底释放可能残留的 RAG 锁
        redis.delete("gulimall:ai:rag:lock");

        trackedConversations.clear();
        trackedImages.clear();
        trackedTasks.clear();
    }

    /**
     * 删除 MinIO 指定前缀下的全部对象。
     *
     * @param prefix 对象前缀
     */
    private void removeMinioPrefix(String prefix) {
        try {
            Iterable<Result<Item>> objects = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(minioBucket)
                            .prefix(prefix)
                            .recursive(true)
                            .build());
            for (Result<Item> result : objects) {
                Item item = result.get();
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(minioBucket)
                        .object(item.objectName())
                        .build());
            }
        } catch (Exception ignored) {
            // 测试清理不掩盖原始断言失败
        }
    }

    /**
     * 真实 SSE 会话，按事件名读取事件。
     */
    protected final class SseSession implements AutoCloseable {

        private final BlockingQueue<SseEvent> queue = new LinkedBlockingQueue<>();
        private final InputStream input;
        private final Thread reader;
        private volatile boolean closed;

        private SseSession(InputStream input) {
            this.input = input;
            this.reader = new Thread(this::read, "agent-sse-test-reader");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        private void read() {
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String eventName = null;
                StringBuilder data = new StringBuilder();
                String line;
                while (!closed && (line = bufferedReader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            queue.offer(new SseEvent(eventName == null ? "message" : eventName,
                                    data.toString()));
                            eventName = null;
                            data.setLength(0);
                        }
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        eventName = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    }
                }
            } catch (Exception ex) {
                queue.offer(new SseEvent("reader-error", ex.getMessage()));
            }
        }

        /**
         * 等待指定事件名的事件。
         *
         * @param event   事件名
         * @param timeout 超时时间
         * @return 事件；超时返回 null
         * @throws InterruptedException 等待被中断
         */
        public SseEvent await(String event, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            List<SseEvent> skipped = new ArrayList<>();
            try {
                while (System.nanoTime() < deadline) {
                    long remain = deadline - System.nanoTime();
                    SseEvent item = queue.poll(Math.max(1, remain), TimeUnit.NANOSECONDS);
                    if (item == null) {
                        return null;
                    }
                    if (event.equals(item.event())) {
                        return item;
                    }
                    skipped.add(item);
                }
                return null;
            } finally {
                queue.addAll(skipped);
            }
        }

        /**
         * 读取剩余全部事件，直到流关闭或超时。
         *
         * @param timeout 超时时间
         * @return 事件列表
         * @throws InterruptedException 等待被中断
         */
        public List<SseEvent> drain(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            List<SseEvent> events = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                long remain = deadline - System.nanoTime();
                SseEvent item = queue.poll(Math.max(1, remain), TimeUnit.NANOSECONDS);
                if (item == null) {
                    break;
                }
                events.add(item);
            }
            return events;
        }

        @Override
        public void close() {
            closed = true;
            try {
                input.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * SSE 事件。
     *
     * @param event 事件名
     * @param data  事件数据
     */
    protected record SseEvent(String event, String data) {

        /**
         * 把 data 解析成 JSON 对象。
         *
         * @param mapper JSON 解析器
         * @return JSON 对象
         * @throws Exception 解析失败
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> json(ObjectMapper mapper) throws Exception {
            return mapper.readValue(data, LinkedHashMap.class);
        }
    }
}
