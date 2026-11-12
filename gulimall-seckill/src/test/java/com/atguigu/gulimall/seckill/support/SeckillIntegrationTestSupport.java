package com.atguigu.gulimall.seckill.support;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.api.seckill.vo.SeckillSkuVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 秒杀真实环境集成测试基类。
 *
 * <p>连接真实 MySQL、Redis、RabbitMQ、Nacos 和其它业务微服务。
 * 测试只创建专用会员、SKU、场次和关联数据，结束后统一清理。
 *
 * <p>运行类测试前需要停止本机 IDEA 中的 gulimall-seckill，
 * 测试进程会自己启动一个完整实例；否则两个实例会抢 MQ 消息和定时任务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
public abstract class SeckillIntegrationTestSupport {

    /** 专用测试会员，所有测试数据都挂在这个会员下，便于清理。 */
    protected static final long TEST_MEMBER_ID = 990001L;

    /** 专用测试 SKU，商品、秒杀关联、仓储库存都使用这个 id。 */
    protected static final long TEST_SKU_ID = 990001L;

    /** 专用测试仓库，仓储库存在这个仓库下单独建一行。 */
    protected static final long TEST_WARE_ID = 990001L;

    protected static final String SESSION_COOKIE = "GULIMALLSESSION";
    protected static final String SESSION_NAME_PREFIX = "IT-SECKILL-";
    protected static final String PROMOTION_TITLE_PREFIX = "IT-SECKILL-";
    protected static final String TEST_USERNAME = "it_seckill";

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate smsJdbc;

    @SuppressWarnings("rawtypes")
    @Autowired
    protected SessionRepository sessionRepository;

    @Autowired
    protected DiscoveryClient discoveryClient;

    @Autowired
    protected AmqpAdmin amqpAdmin;

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Value("${spring.datasource.url}")
    private String smsUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    private final Map<String, JdbcTemplate> jdbcTemplates = new ConcurrentHashMap<>();
    private final Set<Long> trackedSessionIds = new LinkedHashSet<>();
    private final Set<Long> trackedRelationIds = new LinkedHashSet<>();
    private final Set<String> trackedOrderSns = new LinkedHashSet<>();
    private final Set<String> sessionCookies = new LinkedHashSet<>();
    private final Set<String> trackedOperationIds = new LinkedHashSet<>();

    private long baseAddressId;

    /**
     * 创建一套完整测试数据：会员、地址、SKU、仓库库存、场次、关联和 Redis 缓存。
     *
     * @return 测试数据快照
     */
    protected Fixture createFixture() {
        // 1. 默认造一个“已开始 1 小时、还剩 1 小时”的场次：状态 1、库存 100、每人限购 2
        return createFixture(
                new Date(System.currentTimeMillis() - 60 * 60 * 1000L),
                new Date(System.currentTimeMillis() + 60 * 60 * 1000L),
                1, 100, 2);
    }

    /**
     * 创建指定时间窗口的场次。
     *
     * @param start        开始时间
     * @param end          结束时间
     * @param seckillState 场次状态
     * @param stock        秒杀库存
     * @param limit        每人限购
     * @return 测试数据快照
     */
    protected Fixture createFixture(Date start, Date end, int seckillState, int stock, int limit) {
        // 1. 先把会员、地址、SKU、仓库库存这些基础数据准备好，后面的场次都挂在这上面
        ensureBaseData();
        // 2. 插入一场秒杀场次，拿到场次 id
        long sessionId = insertSession(start, end, seckillState);
        // 3. 把测试 SKU 关联到这场秒杀，写入秒杀价、库存和每人限购
        long relationId = insertRelation(sessionId, TEST_SKU_ID, TEST_WARE_ID,
                new BigDecimal("9.90"), stock, limit);
        // 4. 给这场次生成独立随机码，后续下单校验要用
        String randomCode = UUID.randomUUID().toString().replace("-", "");
        // 5. 把场次商品信息和库存写进 Redis，模拟秒杀开始前的缓存预热
        seedRedis(sessionId, start, end, stock, limit, randomCode);
        // 6. 把这次创建的关键 id 和时间窗口打包返回，测试里直接取用
        return new Fixture(TEST_MEMBER_ID, baseAddressId, TEST_SKU_ID, TEST_WARE_ID,
                sessionId, relationId, randomCode, start, end);
    }

    /**
     * 在已有测试会员/SKU 上再建一个场次，用于时间重叠、多场次等场景。
     *
     * @param start        开始时间
     * @param end          结束时间
     * @param seckillState 场次状态
     * @param stock        秒杀库存
     * @param limit        每人限购
     * @return 测试数据快照
     */
    protected Fixture createAdditionalSession(Date start, Date end, int seckillState,
                                              int stock, int limit) {
        // 1. 在已有基础数据上再插一场秒杀场次，用于时间重叠、多场次等场景
        long sessionId = insertSession(start, end, seckillState);
        // 2. 把同一个测试 SKU 关联到新场次，价格、库存、限购都按调用方传入的值写入
        long relationId = insertRelation(sessionId, TEST_SKU_ID, TEST_WARE_ID,
                new BigDecimal("9.90"), stock, limit);
        // 3. 给新场次生成独立随机码，避免和旧场次串用
        String randomCode = UUID.randomUUID().toString().replace("-", "");
        // 4. 把新场次的商品信息和库存写进 Redis
        seedRedis(sessionId, start, end, stock, limit, randomCode);
        // 5. 打包返回新场次的测试数据快照
        return new Fixture(TEST_MEMBER_ID, baseAddressId, TEST_SKU_ID, TEST_WARE_ID,
                sessionId, relationId, randomCode, start, end);
    }

    /**
     * 创建并登录一个真实 Spring Session，返回会话 id。
     *
     * @return GULIMALLSESSION 的值
     */
    protected String loginAsTestMember() {
        // 1. 构造专用测试会员对象，不落库，只用于放进 Session
        MemberResponseVo member = testMember();
        // 2. 新建一个真实 Spring Session，把登录会员写进 LOGIN_USER 属性
        Session session = sessionRepository.createSession();
        session.setAttribute(AuthServerConstant.LOGIN_USER, member);
        // 3. 保存 Session，并记下会话 id 供测试结束统一删除
        sessionRepository.save(session);
        sessionCookies.add(session.getId());
        // 4. 返回会话 id，测试里把它当作 GULIMALLSESSION 的 Cookie 值使用
        return session.getId();
    }

    /**
     * 创建并登录一个指定会员的 Session。
     *
     * @param memberId 会员 id
     * @param username 用户名
     * @return GULIMALLSESSION 的值
     */
    protected String loginAsMember(long memberId, String username) {
        // 1. 以默认测试会员为模板，改掉 id 和用户名，得到指定会员对象
        MemberResponseVo member = testMember();
        member.setId(memberId);
        member.setUsername(username);
        // 2. 新建真实 Session 并写入登录会员，模拟这个会员已经登录
        Session session = sessionRepository.createSession();
        session.setAttribute(AuthServerConstant.LOGIN_USER, member);
        // 3. 保存 Session 并登记会话 id，方便测试结束统一删除
        sessionRepository.save(session);
        sessionCookies.add(session.getId());
        // 4. 返回会话 id 给测试拼 Cookie
        return session.getId();
    }

    /**
     * 测试会员对象，不落库，只用于 Session。
     *
     * @return 会员信息
     */
    protected MemberResponseVo testMember() {
        // 1. 新建一个空的会员对象，只用于 Session，不写入数据库
        MemberResponseVo member = new MemberResponseVo();
        // 2. 依次填入固定的 id、用户名、手机号等资料，保证每次测试拿到的身份一致
        member.setId(TEST_MEMBER_ID);
        member.setUsername(TEST_USERNAME);
        member.setNickname("IT Seckill");
        member.setMobile("19900000001");
        member.setEmail("it@test.local");
        member.setLevelId(1L);
        member.setIntegration(0);
        member.setGrowth(0);
        // 3. 返回这个会员对象，供上面的登录方法写进 Session
        return member;
    }

    /**
     * 直接插入一条秒杀预扣单，用于结果查询、取消、支付等流程。
     *
     * @param fixture   测试数据
     * @param requestId 请求 id
     * @param status    状态
     * @param quantity  数量
     * @return 订单号
     */
    protected String insertSeckillOrder(Fixture fixture, String requestId,
                                        int status, int quantity) {
        // 1. 用时间戳加随机数拼一个唯一订单号，避免多次测试撞号
        String orderSn = "IT" + System.currentTimeMillis()
                + String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
        // 2. 秒杀价固定 9.90，等下按购买数量算出应付金额
        BigDecimal price = new BigDecimal("9.90");
        // 3. 直接往 sms_seckill_order 插一条预扣单，状态和数量由调用方指定
        smsJdbc.update("INSERT INTO sms_seckill_order "
                        + "(order_sn, request_id, member_id, member_username, session_id, sku_id, "
                        + "sku_name, sku_pic, ware_id, quantity, seckill_price, pay_amount, addr_id, "
                        + "pay_type, status, build_retry_count, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW(), NOW())",
                orderSn, requestId, TEST_MEMBER_ID, TEST_USERNAME, fixture.sessionId(),
                fixture.skuId(), "IT 秒杀商品", "https://example.test/sku.png",
                fixture.wareId(), quantity, price, price.multiply(BigDecimal.valueOf(quantity)),
                fixture.addressId(), 1, status);
        // 4. 登记订单号，清理时按它删除秒杀单和相关 MQ 消息
        trackOrderSn(orderSn);
        // 5. 返回订单号给测试继续查询状态或发起支付
        return orderSn;
    }

    /**
     * 清空秒杀建单队列，避免测试消息留给正式实例。
     */
    protected void purgeSeckillOrderCreateQueue() {
        try {
            // 1. 尝试清空秒杀建单队列，避免测试产生的消息留给正式实例消费
            amqpAdmin.purgeQueue(MqConstants.SECKILL_ORDER_CREATE_QUEUE, false);
        } catch (Exception ignored) {
            // 测试清理不掩盖原始断言
        }
    }

    /**
     * 把 Redis 里的秒杀商品信息补回去。
     *
     * @param fixture 测试数据
     */
    protected void seedFixtureRedis(Fixture fixture) {
        // 1. 用 fixture 里保存的场次、时间窗口和随机码，把 Redis 缓存重新写回去
        seedRedis(fixture.sessionId(), fixture.start(), fixture.end(), 100, 2, fixture.randomCode());
    }

    /**
     * 写入场次商品 hash 和库存 key。
     *
     * @param sessionId  场次 id
     * @param start      开始时间
     * @param end        结束时间
     * @param stock      库存
     * @param limit      每人限购
     * @param randomCode 随机码
     */
    protected void seedRedis(long sessionId, Date start, Date end, int stock,
                             int limit, String randomCode) {
        try {
            // 1. 组装一条秒杀商品 VO，字段和真实预热缓存保持一致
            SeckillSkuVo vo = new SeckillSkuVo();
            vo.setPromotionSessionId(sessionId);
            vo.setSkuId(TEST_SKU_ID);
            vo.setWareId(TEST_WARE_ID);
            vo.setSeckillPrice(new BigDecimal("9.90"));
            vo.setSeckillCount(100);
            vo.setSeckillLimit(limit);
            vo.setSeckillSort(1);
            vo.setStartTime(start.getTime());
            vo.setEndTime(end.getTime());
            vo.setRandomCode(randomCode);
            vo.setSkuName("IT 秒杀商品");
            vo.setSkuImg("https://example.test/sku.png");
            vo.setSkuPrice(new BigDecimal("99.00"));

            // 2. 算出缓存 TTL，并拿到这个场次商品 hash 的 key
            Duration ttl = redisTtl(end);
            String infoKey = SeckillConstant.sessionSkusKey(sessionId);
            // 3. 把商品 VO 序列化后写进 hash，再给 hash 设置过期时间
            redis.opsForHash().put(infoKey, String.valueOf(TEST_SKU_ID),
                    objectMapper.writeValueAsString(vo));
            redis.expire(infoKey, ttl);

            // 4. 库存非负时写入库存 key；负数表示故意造一个库存 key 不存在的异常场景
            String stockKey = SeckillConstant.stockKey(sessionId, TEST_SKU_ID);
            if (stock >= 0) {
                redis.opsForValue().set(stockKey, String.valueOf(stock), ttl);
            } else {
                redis.delete(stockKey);
            }
            // 5. 删掉限购计数、请求防重和结算锁，确保这次测试从干净状态开始
            redis.delete(SeckillConstant.userCountKey(sessionId, TEST_SKU_ID));
            redis.delete(SeckillConstant.requestKey(sessionId, TEST_SKU_ID, TEST_MEMBER_ID));
            redis.delete(SeckillConstant.settleLockKey(sessionId));
        } catch (Exception e) {
            // 6. 写缓存失败时转成带上下文的异常抛出，方便测试定位问题
            throw new IllegalStateException("写入秒杀测试缓存失败", e);
        }
    }

    /**
     * 场次缓存 TTL：结束时间 + 1 天。
     *
     * @param end 结束时间
     * @return TTL
     */
    protected Duration redisTtl(Date end) {
        // 1. 把“结束时间 + 1 天”换算成剩余毫秒数，作为缓存存活时间
        long millis = end.getTime() + 24 * 60 * 60 * 1000L - System.currentTimeMillis();
        // 2. 至少保留 1 分钟，避免场次已过期时算出负数 TTL
        return Duration.ofMillis(Math.max(millis, 60_000L));
    }

    /**
     * 读取秒杀库存。
     *
     * @param sessionId 场次 id
     * @return 库存；key 不存在返回 null
     */
    protected Integer redisStock(long sessionId) {
        // 1. 按场次和测试 SKU 拼出库存 key，读出 Redis 里的字符串值
        String value = redis.opsForValue().get(SeckillConstant.stockKey(sessionId, TEST_SKU_ID));
        // 2. key 不存在返回 null，存在就转成整数返回
        return value == null ? null : Integer.valueOf(value);
    }

    /**
     * 读取 Redis 限购计数。
     *
     * @param sessionId 场次 id
     * @return 已购数量；key 不存在返回 0
     */
    protected int redisBought(long sessionId) {
        // 1. 从限购计数 hash 里取当前测试会员已经买了多少件
        Object value = redis.opsForHash().get(
                SeckillConstant.userCountKey(sessionId, TEST_SKU_ID),
                String.valueOf(TEST_MEMBER_ID));
        // 2. key 不存在按 0 处理，存在就转成整数返回
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }

    /**
     * 记录测试创建的订单号，清理时使用。
     *
     * @param orderSn 订单号
     */
    protected void trackOrderSn(String orderSn) {
        // 1. 订单号非空时才登记，避免把 null 或空白值放进清理集合
        if (orderSn != null && !orderSn.isBlank()) {
            trackedOrderSns.add(orderSn);
        }
    }

    /**
     * 记录测试创建的库存操作 id。
     *
     * @param operationId 操作 id
     */
    protected void trackOperationId(String operationId) {
        // 1. 操作 id 非空时才登记，方便清理时按它删除库存操作记录
        if (operationId != null && !operationId.isBlank()) {
            trackedOperationIds.add(operationId);
        }
    }

    /**
     * 记录通过其它服务创建的场次 id，清理 Redis 和 MQ 时使用。
     *
     * @param sessionId 场次 id
     */
    protected void trackSessionId(long sessionId) {
        // 1. 把外部服务创建的场次 id 登记下来，清理 Redis 和 MQ 时按它删除
        trackedSessionIds.add(sessionId);
    }

    /**
     * 拼出本测试实例的地址。
     *
     * @param path 路径
     * @return 完整 URL
     */
    protected String localUrl(String path) {
        // 1. 用测试实例的随机端口拼出完整地址，保证请求打到当前启动的服务
        return "http://localhost:" + port + path;
    }

    /**
     * 发送 GET JSON 请求。
     *
     * @param path      路径
     * @param sessionId 会话 id，可为 null
     * @return 响应 JSON
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> getJson(String path, String sessionId) {
        // 1. 带上会话 Cookie 向本实例发 GET 请求，把响应体按 Map 返回
        return rest.exchange(localUrl(path), HttpMethod.GET,
                new HttpEntity<>(headers(sessionId)), Map.class).getBody();
    }

    /**
     * 发送 POST JSON 请求。
     *
     * @param path      路径
     * @param body      请求体
     * @param sessionId 会话 id，可为 null
     * @return 响应 JSON
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> postJson(String path, Object body, String sessionId) {
        // 1. 带上会话 Cookie 和 JSON 请求体发 POST，把响应体按 Map 返回
        return rest.exchange(localUrl(path), HttpMethod.POST,
                new HttpEntity<>(body, headers(sessionId)), Map.class).getBody();
    }

    /**
     * 发送 POST 表单请求。
     *
     * @param path      路径
     * @param form      表单
     * @param sessionId 会话 id，可为 null
     * @return 响应 JSON
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> postForm(String path, Map<String, String> form, String sessionId) {
        // 1. 把普通 Map 表单转成 Spring 的 MultiValueMap，避免丢重复字段
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        // 2. 准备带会话 Cookie 的请求头，并把 Content-Type 改成表单类型
        HttpHeaders headers = headers(sessionId);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // 3. 发 POST 表单请求，返回解析后的响应 Map
        return rest.exchange(localUrl(path), HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class).getBody();
    }

    /**
     * 发送 GET 页面请求。
     *
     * @param path      路径
     * @param sessionId 会话 id，可为 null
     * @return 页面 HTML
     */
    protected String getHtml(String path, String sessionId) {
        // 1. 准备带会话 Cookie 的请求头，并声明只接受 HTML
        HttpHeaders headers = headers(sessionId);
        headers.setAccept(List.of(MediaType.TEXT_HTML));
        // 2. 发 GET 请求拿页面，最后返回 HTML 正文
        ResponseEntity<String> response = rest.exchange(localUrl(path), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        return response.getBody();
    }

    /**
     * 用不跟随重定向的 HTTP 客户端读取响应。
     *
     * @param path      路径
     * @param sessionId 会话 id，可为 null
     * @return 原始响应
     * @throws Exception 请求失败
     */
    protected RawResponse rawGet(String path, String sessionId) throws Exception {
        // 1. 建一个不跟随重定向的 HttpClient，方便断言 302 之类的原始响应
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        // 2. 组装 GET 请求，并给请求设置 10 秒超时
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(localUrl(path)))
                .timeout(Duration.ofSeconds(10))
                .GET();
        // 3. 有会话时补上 GULIMALLSESSION Cookie，服务端才能认出登录状态
        if (sessionId != null) {
            builder.header(HttpHeaders.COOKIE, SESSION_COOKIE + "=" + cookieValue(sessionId));
        }
        // 4. 真正发送请求，拿到状态码、响应头和响应体
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        // 5. 包成 RawResponse 返回，调用方可以自己检查状态码和响应头
        return new RawResponse(response.statusCode(), response.headers().map(), response.body());
    }

    /**
     * 跨服务 GET JSON。
     *
     * @param baseUrl 服务地址
     * @param path    路径
     * @return 响应 JSON
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> getServiceJson(String baseUrl, String path) {
        // 1. 拼上目标服务地址发无会话的 GET JSON 请求，返回响应 Map
        return rest.exchange(baseUrl + path, HttpMethod.GET,
                new HttpEntity<>(headers(null)), Map.class).getBody();
    }

    /**
     * 跨服务 POST JSON。
     *
     * @param baseUrl 服务地址
     * @param path    路径
     * @param body    请求体
     * @return 响应 JSON
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> postServiceJson(String baseUrl, String path, Object body) {
        // 1. 不带会话，直接转调四参版本发跨服务 POST JSON
        return postServiceJson(baseUrl, path, body, null);
    }

    /**
     * 跨服务 POST JSON，可带登录会话。
     *
     * @param baseUrl   服务地址
     * @param path      路径
     * @param body      请求体
     * @param sessionId 会话 id
     * @return 响应 JSON
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> postServiceJson(String baseUrl, String path,
                                                  Object body, String sessionId) {
        // 1. 带指定会话 Cookie，向目标服务发 POST JSON 并返回响应 Map
        return rest.exchange(baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, headers(sessionId)), Map.class).getBody();
    }

    /**
     * 跨服务 POST 表单。
     *
     * @param baseUrl 服务地址
     * @param path    路径
     * @param form    表单
     * @return 响应 JSON
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> postServiceForm(String baseUrl, String path,
                                                  Map<String, String> form) {
        // 1. 不带会话，直接转调四参版本发跨服务 POST 表单
        return postServiceForm(baseUrl, path, form, null);
    }

    /**
     * 跨服务 POST 表单，可带登录会话。
     *
     * @param baseUrl   服务地址
     * @param path      路径
     * @param form      表单
     * @param sessionId 会话 id
     * @return 响应 JSON
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> postServiceForm(String baseUrl, String path,
                                                  Map<String, String> form, String sessionId) {
        // 1. 把普通 Map 表单转成 MultiValueMap，保留所有键值
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        // 2. 准备请求头并设置表单 Content-Type，有会话时还会带上 Cookie
        HttpHeaders headers = headers(sessionId);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // 3. 向目标服务发 POST 表单请求，返回响应 Map
        return rest.exchange(baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class).getBody();
    }

    /**
     * 请求头，带真实会话 Cookie。
     *
     * @param sessionId 会话 id，可为 null
     * @return 请求头
     */
    protected HttpHeaders headers(String sessionId) {
        // 1. 新建请求头，默认按 JSON 发送
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 2. 有会话时把会话 id 做 Base64 编码后塞进 Cookie
        if (sessionId != null) {
            headers.add(HttpHeaders.COOKIE, SESSION_COOKIE + "=" + cookieValue(sessionId));
        }
        // 3. 返回组装好的请求头
        return headers;
    }

    /**
     * Spring Session 默认对 Cookie 值做 Base64 编码，发送 Cookie 时必须同样编码。
     *
     * @param sessionId 原始会话 id
     * @return Cookie 值
     */
    protected String cookieValue(String sessionId) {
        // 1. 按 Spring Session 的规则把会话 id 做 Base64 编码，再作为 Cookie 值返回
        return java.util.Base64.getEncoder().encodeToString(
                sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 断言统一响应成功。
     *
     * @param response 响应
     */
    protected void assertOk(Map<String, Object> response) {
        // 1. 响应体和 code 字段都不能为空，否则直接断言失败
        assertNotNull(response, "接口没有返回响应体");
        Object code = response.get("code");
        assertNotNull(code, "响应没有 code");
        // 2. code 不是 0 说明接口失败，把完整响应打出来方便排查
        if (!Integer.valueOf(0).equals(Integer.valueOf(String.valueOf(code)))) {
            fail("接口返回失败：" + response);
        }
    }

    /**
     * 断言统一响应是预期错误码。
     *
     * @param response 响应
     * @param code     错误码
     */
    protected void assertCode(Map<String, Object> response, int code) {
        // 1. 响应体和 code 字段都不能为空
        assertNotNull(response, "接口没有返回响应体");
        Object actual = response.get("code");
        assertNotNull(actual, "响应没有 code");
        // 2. 把实际 code 转成整数和期望值比较，不一致就打印响应内容
        if (Integer.parseInt(String.valueOf(actual)) != code) {
            fail("错误码不匹配，期望 " + code + "，响应：" + response);
        }
    }

    /**
     * 从抢购响应里取秒杀单对象。
     *
     * @param response 抢购响应
     * @return 秒杀单
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> orderResult(Map<String, Object> response) {
        // 1. 响应体和 seckillOrder 字段都不能为空，否则抢购结果没法继续断言
        assertNotNull(response, "抢购没有返回响应体");
        Object value = response.get("seckillOrder");
        assertNotNull(value, "响应没有 seckillOrder：" + response);
        // 2. seckillOrder 是嵌套 Map，强转后返回给测试继续取字段
        return (Map<String, Object>) value;
    }

    /**
     * 从场次响应里取列表。
     *
     * @param response 响应
     * @param key      字段名
     * @return 列表
     */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> listValue(Map<String, Object> response, String key) {
        // 1. 响应体和指定 key 的字段都不能为空
        assertNotNull(response, "响应为空");
        Object value = response.get(key);
        assertNotNull(value, "响应没有 " + key + "：" + response);
        // 2. 把字段强转成列表返回，调用方直接遍历
        return (List<Map<String, Object>>) value;
    }

    /**
     * 从响应里取对象。
     *
     * @param response 响应
     * @param key      字段名
     * @return 对象
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> mapValue(Map<String, Object> response, String key) {
        // 1. 响应体和指定 key 的字段都不能为空
        assertNotNull(response, "响应为空");
        Object value = response.get(key);
        assertNotNull(value, "响应没有 " + key + "：" + response);
        // 2. 把字段强转成嵌套 Map 返回
        return (Map<String, Object>) value;
    }

    /**
     * 通过服务发现拿到服务地址；服务未启动时跳过当前测试。
     *
     * @param serviceId 服务名
     * @return 服务地址
     */
    protected String requireService(String serviceId) {
        // 1. 从注册中心查这个服务当前有哪些实例
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
        // 2. 一个实例都没有说明服务没起，用 Assumptions 跳过当前测试而不是判失败
        Assumptions.assumeFalse(instances.isEmpty(), serviceId + " 未启动，跳过当前测试");
        // 3. 有实例就取第一个的地址返回，供跨服务请求使用
        return instances.get(0).getUri().toString();
    }

    /**
     * 服务是否已启动。
     *
     * @param serviceId 服务名
     * @return 是否有实例
     */
    protected boolean serviceUp(String serviceId) {
        // 1. 查注册中心，实例列表非空就认为这个服务已经启动
        return !discoveryClient.getInstances(serviceId).isEmpty();
    }

    /**
     * 轮询等待条件成立。
     *
     * @param description 描述
     * @param timeout     超时
     * @param condition   条件
     * @throws InterruptedException 等待被中断
     */
    protected void awaitUntil(String description, Duration timeout,
                              Supplier<Boolean> condition) throws InterruptedException {
        // 1. 先算出超时截止时间，用纳秒计时避免系统时钟调整干扰判断
        long deadline = System.nanoTime() + timeout.toNanos();
        // 2. 在截止时间前每 200 毫秒检查一次条件，一旦成立立刻返回
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(200);
        }
        // 3. 超时仍未成立就断言失败，并带上描述方便定位是哪一步没等到
        fail("等待超时：" + description);
    }

    /**
     * 按库名取 JdbcTemplate，当前主数据源是 gulimall_sms。
     *
     * @param schema 库名
     * @return JdbcTemplate
     */
    protected JdbcTemplate jdbc(String schema) {
        // 1. 按库名缓存 JdbcTemplate，同一个库只建一次，避免反复创建数据源
        return jdbcTemplates.computeIfAbsent(schema, name -> {
            // 2. 把主库 URL 里的 gulimall_sms 换成目标库名，先兼容带查询参数的写法
            String url = smsUrl.replace("/gulimall_sms?", "/" + name + "?");
            if (url.equals(smsUrl)) {
                url = smsUrl.replace("/gulimall_sms", "/" + name);
            }
            // 3. 用主库的账号密码新建一个指向目标库的数据源
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(url);
            dataSource.setUsername(dbUsername);
            dataSource.setPassword(dbPassword);
            // 4. 用这个数据源包装出 JdbcTemplate 返回
            return new JdbcTemplate(dataSource);
        });
    }

    /**
     * 查询单条秒杀订单。
     *
     * @param orderSn 订单号
     * @return 订单行；不存在返回 null
     */
    protected Map<String, Object> seckillOrderRow(String orderSn) {
        // 1. 按订单号到秒杀库查订单，最多只取一条
        List<Map<String, Object>> rows = smsJdbc.queryForList(
                "SELECT * FROM sms_seckill_order WHERE order_sn = ? LIMIT 1", orderSn);
        // 2. 没有记录返回 null，有记录返回第一行，方便调用方判空
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查询秒杀订单状态。
     *
     * @param orderSn 订单号
     * @return 状态；不存在返回 null
     */
    protected Integer seckillOrderStatus(String orderSn) {
        // 1. 复用查询方法拿到秒杀订单行
        Map<String, Object> row = seckillOrderRow(orderSn);
        // 2. 订单不存在或状态字段为空时返回 null
        if (row == null || row.get("status") == null) {
            return null;
        }
        // 3. 数据库返回的可能是各种 Number，统一转成 int 返回
        return ((Number) row.get("status")).intValue();
    }

    /**
     * 查询仓储秒杀库存行。
     *
     * @return stock、stock_locked、seckill_stock_locked
     */
    protected Map<String, Object> wareSkuRow() {
        // 1. 到仓储库按测试 SKU 和仓库 id 查库存行
        List<Map<String, Object>> rows = jdbc("gulimall_wms").queryForList(
                "SELECT * FROM wms_ware_sku WHERE sku_id = ? AND ware_id = ? LIMIT 1",
                TEST_SKU_ID, TEST_WARE_ID);
        // 2. 没有记录返回 null，有记录返回第一行
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 把仓储测试库存重置为指定值。
     *
     * @param stock 库存
     */
    protected void resetWareStock(int stock) {
        // 1. 总库存设成指定值，两个锁定字段清零，相当于把仓储库存恢复成干净状态
        setWareStock(stock, 0, 0);
    }

    /**
     * 设置仓储测试库存的三个口径。
     *
     * @param stock         总库存
     * @param stockLocked   普通订单锁定
     * @param seckillLocked 秒杀活动锁定
     */
    protected void setWareStock(int stock, int stockLocked, int seckillLocked) {
        // 1. 按测试 SKU 和仓库更新三个库存口径：总库存、普通锁定、秒杀活动锁定
        jdbc("gulimall_wms").update(
                "UPDATE wms_ware_sku SET stock = ?, stock_locked = ?, seckill_stock_locked = ? "
                        + "WHERE sku_id = ? AND ware_id = ?",
                stock, stockLocked, seckillLocked, TEST_SKU_ID, TEST_WARE_ID);
    }

    /**
     * 取响应头第一个值。
     *
     * @param response 原始响应
     * @param name     头名（小写）
     * @return 值；不存在返回 null
     */
    protected String firstHeader(RawResponse response, String name) {
        // 1. 按小写头名从原始响应里取出该头的所有值
        List<String> values = response.headers().get(name);
        // 2. 没有这个头返回 null，有就返回第一个值
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * 查询订单主表。
     *
     * @param orderSn 订单号
     * @return 订单行；不存在返回 null
     */
    protected Map<String, Object> omsOrderRow(String orderSn) {
        // 1. 到订单库按订单号查 oms_order 主表，最多取一条
        List<Map<String, Object>> rows = jdbc("gulimall_oms").queryForList(
                "SELECT * FROM oms_order WHERE order_sn = ? LIMIT 1", orderSn);
        // 2. 没有记录返回 null，有记录返回第一行
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 更新秒杀单状态和订单 id。
     *
     * @param orderSn 订单号
     * @param status  状态
     * @param orderId 订单 id
     */
    protected void updateSeckillOrder(String orderSn, int status, Long orderId) {
        // 1. 按订单号更新秒杀单状态和它关联的正式订单 id
        smsJdbc.update("UPDATE sms_seckill_order SET status = ?, order_id = ? "
                + "WHERE order_sn = ?", status, orderId, orderSn);
    }

    /**
     * 建立测试会员、地址、SKU 和仓库库存。
     */
    protected void ensureBaseData() {
        // 1. 拿到会员库连接，先删掉上次可能残留的测试地址和测试会员
        JdbcTemplate ums = jdbc("gulimall_ums");
        ums.update("DELETE FROM ums_member_receive_address WHERE member_id = ?", TEST_MEMBER_ID);
        ums.update("DELETE FROM ums_member WHERE id = ?", TEST_MEMBER_ID);
        // 2. 插入新的测试会员，id、用户名等固定值保证测试可以重复执行
        ums.update("INSERT INTO ums_member "
                        + "(id, level_id, username, password, nickname, mobile, email, header, "
                        + "gender, integration, growth, status, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                TEST_MEMBER_ID, 1L, TEST_USERNAME, "", "IT Seckill",
                "19900000001", "it@test.local", "", 1, 0, 0, 1);

        // 3. 插入默认收货地址，再用自增主键回填 baseAddressId，后面下单要用
        KeyHolder addressKey = new GeneratedKeyHolder();
        ums.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ums_member_receive_address "
                            + "(member_id, name, phone, post_code, province, city, region, "
                            + "detail_address, areacode, default_status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, TEST_MEMBER_ID);
            statement.setString(2, "IT 收货人");
            statement.setString(3, "19900000001");
            statement.setString(4, "000000");
            statement.setString(5, "北京市");
            statement.setString(6, "北京市");
            statement.setString(7, "朝阳区");
            statement.setString(8, "IT 测试地址");
            statement.setString(9, "110105");
            statement.setInt(10, 1);
            return statement;
        }, addressKey);
        baseAddressId = Objects.requireNonNull(addressKey.getKey()).longValue();

        // 4. 到商品库先删后插测试 SKU，保证商品信息存在且内容是最新的
        JdbcTemplate pms = jdbc("gulimall_pms");
        pms.update("DELETE FROM pms_sku_info WHERE sku_id = ?", TEST_SKU_ID);
        pms.update("INSERT INTO pms_sku_info "
                        + "(sku_id, spu_id, sku_name, sku_desc, catelog_id, brand_id, "
                        + "sku_default_img, sku_title, sku_subtitle, price, sale_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TEST_SKU_ID, TEST_SKU_ID, "IT 秒杀商品", "集成测试商品",
                0L, 0L, "https://example.test/sku.png", "IT 秒杀商品",
                "集成测试商品", new BigDecimal("99.00"), 0L);

        // 5. 到仓储库先删后插测试库存行，初始总库存 100、两个锁定字段都是 0
        JdbcTemplate wms = jdbc("gulimall_wms");
        wms.update("DELETE FROM wms_ware_sku WHERE sku_id = ? AND ware_id = ?",
                TEST_SKU_ID, TEST_WARE_ID);
        wms.update("INSERT INTO wms_ware_sku "
                        + "(sku_id, ware_id, stock, sku_name, stock_locked, seckill_stock_locked) "
                        + "VALUES (?, ?, ?, ?, 0, 0)",
                TEST_SKU_ID, TEST_WARE_ID, 100, "IT 秒杀商品");
    }

    private long insertSession(Date start, Date end, int seckillState) {
        // 1. 准备回填自增主键的 KeyHolder，并生成带纳秒后缀的场次名，避免重名
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String name = SESSION_NAME_PREFIX + System.nanoTime();
        // 2. 插入秒杀场次，status 固定为 1，seckill_state 由调用方指定
        smsJdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sms_seckill_session "
                            + "(promotion_id, name, start_time, end_time, status, seckill_state, create_time) "
                            + "VALUES (NULL, ?, ?, ?, 1, ?, NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setTimestamp(2, new Timestamp(start.getTime()));
            statement.setTimestamp(3, new Timestamp(end.getTime()));
            statement.setInt(4, seckillState);
            return statement;
        }, keyHolder);
        // 3. 取回自增 id，登记到清理集合，最后返回给调用方
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        trackedSessionIds.add(id);
        return id;
    }

    private long insertRelation(long sessionId, long skuId, long wareId,
                                BigDecimal price, int count, int limit) {
        // 1. 准备回填自增主键的 KeyHolder
        KeyHolder keyHolder = new GeneratedKeyHolder();
        // 2. 插入场次和 SKU 的关联，写入秒杀价、库存、限购和排序
        smsJdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sms_seckill_sku_relation "
                            + "(promotion_id, promotion_session_id, sku_id, ware_id, seckill_price, "
                            + "seckill_count, seckill_limit, seckill_sort, version) "
                            + "VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, sessionId);
            statement.setLong(2, skuId);
            statement.setLong(3, wareId);
            statement.setBigDecimal(4, price);
            statement.setInt(5, count);
            statement.setInt(6, limit);
            statement.setInt(7, 1);
            return statement;
        }, keyHolder);
        // 3. 取回关联 id，登记到清理集合后返回给调用方
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        trackedRelationIds.add(id);
        return id;
    }

    /**
     * 清理本测试创建的数据库、Redis、Session 和 MQ 数据。
     */
    @AfterEach
    void cleanupTestData() {
        try {
            // 1. 按库依次清理秒杀、订单、仓储、Redis 和会话数据
            cleanupSms();
            cleanupOms();
            cleanupWms();
            cleanupRedis();
            cleanupSessions();
            // 2. 最后删掉测试会员、地址和 SKU 这些基础数据
            cleanupBaseData();
        } catch (Exception e) {
            // 3. 清理失败只打印日志，不能掩盖测试本身真正的断言失败
            System.err.println("清理测试数据失败：" + e.getMessage());
            e.printStackTrace();
        } finally {
            // 4. 不管清理是否成功，都清空当前测试的追踪集合，防止污染下一个测试
            trackedSessionIds.clear();
            trackedRelationIds.clear();
            trackedOrderSns.clear();
            sessionCookies.clear();
            trackedOperationIds.clear();
            // 5. 兜底恢复 MQ 重试间隔，避免前面的测试改小后影响后续用例
            // MqConstants.RETRY_DELAY_MILLIS 是可变数组，Flow19 会临时改小，这里兜底恢复
            long[] delays = MqConstants.RETRY_DELAY_MILLIS;
            delays[0] = 10_000L;
            delays[1] = 30_000L;
            delays[2] = 60_000L;
        }
    }

    private void cleanupSms() {
        // 1. 先按登记过的订单号删掉秒杀单和对应的 MQ 消息
        for (String orderSn : trackedOrderSns) {
            smsJdbc.update("DELETE FROM sms_seckill_order WHERE order_sn = ?", orderSn);
            smsJdbc.update("DELETE FROM mq_message WHERE content LIKE ?", "%" + orderSn + "%");
            smsJdbc.update("DELETE FROM mq_dead_message WHERE content LIKE ?", "%" + orderSn + "%");
        }
        // 2. 再按测试会员兜底删一遍秒杀单和内容里带会员 id 的 MQ 消息
        smsJdbc.update("DELETE FROM sms_seckill_order WHERE member_id = ?", TEST_MEMBER_ID);
        smsJdbc.update("DELETE FROM mq_message WHERE content LIKE ?", "%" + TEST_MEMBER_ID + "%");
        // 3. 按登记过的场次 id 删掉 MQ 里引用了这个场次的消息
        for (Long sessionId : trackedSessionIds) {
            smsJdbc.update("DELETE FROM mq_message WHERE content LIKE ?",
                    "%\"sessionId\":" + sessionId + "%");
            smsJdbc.update("DELETE FROM mq_dead_message WHERE content LIKE ?",
                    "%\"sessionId\":" + sessionId + "%");
        }
        // 4. 按场次名前缀删掉秒杀关联、场次和对应促销活动，专挑 IT-SECKILL- 前缀的数据
        smsJdbc.update("DELETE FROM sms_seckill_sku_relation WHERE promotion_session_id IN "
                + "(SELECT id FROM sms_seckill_session WHERE name LIKE ?)", SESSION_NAME_PREFIX + "%");
        smsJdbc.update("DELETE FROM sms_seckill_session WHERE name LIKE ?", SESSION_NAME_PREFIX + "%");
        smsJdbc.update("DELETE FROM sms_seckill_promotion WHERE title LIKE ?",
                PROMOTION_TITLE_PREFIX + "%");
        // 5. 最后删掉测试会员的秒杀提醒记录
        smsJdbc.update("DELETE FROM sms_seckill_sku_notice WHERE member_id = ?", TEST_MEMBER_ID);
    }

    private void cleanupOms() {
        // 1. 拿到订单库连接
        JdbcTemplate oms = jdbc("gulimall_oms");
        // 2. 按登记过的订单号，从退款、退货、操作历史、明细、支付到主表逐层删除
        for (String orderSn : trackedOrderSns) {
            oms.update("DELETE r FROM oms_refund_info r JOIN oms_order_return_apply a "
                    + "ON r.order_return_id = a.id WHERE a.order_sn = ?", orderSn);
            oms.update("DELETE FROM oms_order_return_apply WHERE order_sn = ?", orderSn);
            oms.update("DELETE h FROM oms_order_operate_history h JOIN oms_order o "
                    + "ON h.order_id = o.id WHERE o.order_sn = ?", orderSn);
            oms.update("DELETE FROM oms_order_item WHERE order_sn = ?", orderSn);
            oms.update("DELETE FROM oms_payment_info WHERE order_sn = ?", orderSn);
            oms.update("DELETE FROM oms_order WHERE order_sn = ?", orderSn);
        }
        // 3. 再按测试会员兜底清理订单明细、操作历史、支付和退款退货数据
        oms.update("DELETE i FROM oms_order_item i JOIN oms_order o ON i.order_id = o.id "
                + "WHERE o.member_id = ?", TEST_MEMBER_ID);
        oms.update("DELETE h FROM oms_order_operate_history h JOIN oms_order o "
                + "ON h.order_id = o.id WHERE o.member_id = ?", TEST_MEMBER_ID);
        oms.update("DELETE p FROM oms_payment_info p JOIN oms_order o "
                + "ON p.order_sn = o.order_sn WHERE o.member_id = ?", TEST_MEMBER_ID);
        oms.update("DELETE r FROM oms_refund_info r JOIN oms_order_return_apply a "
                + "ON r.order_return_id = a.id JOIN oms_order o ON a.order_sn = o.order_sn "
                + "WHERE o.member_id = ?", TEST_MEMBER_ID);
        oms.update("DELETE a FROM oms_order_return_apply a JOIN oms_order o "
                + "ON a.order_sn = o.order_sn WHERE o.member_id = ?", TEST_MEMBER_ID);
        // 4. 最后删掉这个会员的订单主表记录
        oms.update("DELETE FROM oms_order WHERE member_id = ?", TEST_MEMBER_ID);
    }

    private void cleanupWms() {
        // 1. 拿到仓储库连接
        JdbcTemplate wms = jdbc("gulimall_wms");
        // 2. 按登记过的订单号删掉仓储任务明细、任务和对应 MQ 消息
        for (String orderSn : trackedOrderSns) {
            wms.update("DELETE d FROM wms_ware_order_task_detail d "
                    + "JOIN wms_ware_order_task t ON d.task_id = t.id WHERE t.order_sn = ?", orderSn);
            wms.update("DELETE FROM wms_ware_order_task WHERE order_sn = ?", orderSn);
            wms.update("DELETE FROM mq_message WHERE content LIKE ?", "%" + orderSn + "%");
            wms.update("DELETE FROM mq_dead_message WHERE content LIKE ?", "%" + orderSn + "%");
        }
        // 3. 再按测试会员兜底删掉仓储任务明细和任务
        wms.update("DELETE d FROM wms_ware_order_task_detail d "
                + "JOIN wms_ware_order_task t ON d.task_id = t.id WHERE t.member_id = ?",
                TEST_MEMBER_ID);
        wms.update("DELETE FROM wms_ware_order_task WHERE member_id = ?", TEST_MEMBER_ID);
        // 4. 删掉测试 SKU 的秒杀库存操作记录，再按登记过的操作 id 补删一遍
        wms.update("DELETE FROM wms_seckill_stock_operation WHERE sku_id = ?", TEST_SKU_ID);
        for (String operationId : trackedOperationIds) {
            wms.update("DELETE FROM wms_seckill_stock_operation WHERE operation_id = ?", operationId);
        }
        // 5. 清掉含测试会员 id 和 bad-json 的 MQ 消息，最后删掉测试库存行
        wms.update("DELETE FROM mq_message WHERE content LIKE ?", "%" + TEST_MEMBER_ID + "%");
        wms.update("DELETE FROM mq_dead_message WHERE content LIKE ?", "%bad-json%");
        wms.update("DELETE FROM wms_ware_sku WHERE sku_id = ? AND ware_id = ?",
                TEST_SKU_ID, TEST_WARE_ID);
    }

    private void cleanupRedis() {
        // 1. 按登记过的场次 id 删掉商品 hash、库存、限购计数、防重和结算锁
        for (Long sessionId : trackedSessionIds) {
            redis.delete(SeckillConstant.sessionSkusKey(sessionId));
            redis.delete(SeckillConstant.stockKey(sessionId, TEST_SKU_ID));
            redis.delete(SeckillConstant.userCountKey(sessionId, TEST_SKU_ID));
            redis.delete(SeckillConstant.requestKey(sessionId, TEST_SKU_ID, TEST_MEMBER_ID));
            redis.delete(SeckillConstant.settleLockKey(sessionId));
        }
        // 2. 按登记过的订单号删掉预扣和释放 key，防止订单状态串到下一个测试
        for (String orderSn : trackedOrderSns) {
            redis.delete(SeckillConstant.reserveKey(orderSn));
            redis.delete(SeckillConstant.releaseKey(orderSn));
        }
        // 3. 兜底扫一遍场次商品 hash 前缀，命中已知测试场次的 key 也一并删掉
        // 清理可能残留的测试请求防重 key
        Set<String> keys = redis.keys(SeckillConstant.SECKILL_SESSION_SKUS_PREFIX + "*");
        if (keys != null) {
            for (String key : keys) {
                String[] parts = key.split(":");
                if (parts.length >= 3) {
                    try {
                        long sessionId = Long.parseLong(parts[2]);
                        if (trackedSessionIds.contains(sessionId)) {
                            redis.delete(key);
                        }
                    } catch (NumberFormatException ignored) {
                        // 非测试场次 key，不动
                    }
                }
            }
        }
        // 4. 清掉秒杀库里残留的 bad-json 测试消息和死信
        smsJdbc.update("DELETE FROM mq_message WHERE content LIKE ?", "%bad-json%");
        smsJdbc.update("DELETE FROM mq_dead_message WHERE content LIKE ?", "%bad-json%");
    }

    private void cleanupSessions() {
        // 1. 遍历当前测试创建的会话 id，逐个从 Session 仓库删除
        for (String sessionId : sessionCookies) {
            try {
                sessionRepository.deleteById(sessionId);
            } catch (Exception ignored) {
                // 会话可能已经过期
            }
        }
    }

    private void cleanupBaseData() {
        // 1. 删掉测试 SKU，避免商品库里留下测试商品
        jdbc("gulimall_pms").update("DELETE FROM pms_sku_info WHERE sku_id = ?", TEST_SKU_ID);
        // 2. 删掉测试会员的默认收货地址
        jdbc("gulimall_ums").update(
                "DELETE FROM ums_member_receive_address WHERE member_id = ?", TEST_MEMBER_ID);
        // 3. 最后删掉测试会员本身
        jdbc("gulimall_ums").update("DELETE FROM ums_member WHERE id = ?", TEST_MEMBER_ID);
    }

    /**
     * 测试数据快照。
     *
     * @param memberId   会员 id
     * @param addressId  默认地址 id
     * @param skuId      SKU id
     * @param wareId     仓库 id
     * @param sessionId  场次 id
     * @param relationId 关联 id
     * @param randomCode 随机码
     * @param start      开始时间
     * @param end        结束时间
     */
    protected record Fixture(long memberId, long addressId, long skuId, long wareId,
                             long sessionId, long relationId, String randomCode,
                             Date start, Date end) {
    }

    /**
     * 不跟随重定向的原始响应。
     *
     * @param status  状态码
     * @param headers 响应头
     * @param body    响应体
     */
    protected record RawResponse(int status, Map<String, List<String>> headers, String body) {
    }
}
