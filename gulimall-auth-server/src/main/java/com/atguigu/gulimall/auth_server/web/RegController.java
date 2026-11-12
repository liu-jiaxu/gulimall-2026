package com.atguigu.gulimall.auth_server.web;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.atguigu.gulimall.api.auth_server.UserRegisterVo;
import com.atguigu.gulimall.auth_server.feign.MemberFeignClient;
import com.atguigu.gulimall.auth_server.feign.SmsFeignClient;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.utils.R;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @ClassName: RegController
 * @Package: com.atguigu.gulimall.auth_server.web
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/1 - 15:47
 * @Version: v1.0
 */
@Controller
@Slf4j
public class RegController {

    /** 同一个手机号的发送间隔（毫秒）—— 5 分钟内只能重新发送一次 */
    private static final long SEND_INTERVAL_MS = 5 * 60 * 1000L;
    /** 验证码在 Redis 里的存活时间（分钟） */
    private static final long CODE_TTL_MINUTES = 10;

    /** 注册失败提示在 Redis 里的 key 前缀 */
    private static final String REG_ERROR_CACHE_PREFIX = "reg:error:";
    /** 注册失败提示的存活时间（分钟）—— 重定向回来够用户看一眼就行，不必长留 */
    private static final long REG_ERROR_TTL_MINUTES = 5;

    /** 重定向回登录页/注册页时要用 */
    @Value("${gulimall.auth-url}")
    private String authUrl;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private SmsFeignClient smsFeignClient;
    @Autowired
    private MemberFeignClient memberFeignClient;

    @GetMapping(path = "/reg.html")
    public String reg(Model model,
                      @RequestParam(value = "errors", required = false) String errorToken) {

        // 验证码冷却剩余秒数，默认 0（不在冷却期）。
        // 模板据此决定要不要一进来就把按钮切成倒计时状态
        model.addAttribute("smsCooldownSeconds", 0);

        // 注册失败重定向回来时，URL 上带着一次性 token。
        // getAndDelete 是【原子】的"取出并删除" —— 所以这组数据只会被取到一次，
        // 用户按 F5 刷新时已经取不到了（符合预期：提示看完就该消失）
        if (StringUtils.isNotEmpty(errorToken)) {
            String json = stringRedisTemplate.opsForValue()
                    .getAndDelete(REG_ERROR_CACHE_PREFIX + errorToken);
            if (StringUtils.isNotEmpty(json)) {
                Map<String, Object> payload = JSON.parseObject(
                        json, new TypeReference<Map<String, Object>>() {
                        });

                // 错误提示：字段名 -> 提示语。模板用 #maps.containsKey(errors, 'xxx') 取
                model.addAttribute("errors", payload.get("errors"));

                // 回填用户刚才填过的内容。
                // ⚠️ 只有用户名和手机号 —— 密码类字段【不在 payload 里】（见 redirectWithErrors）
                model.addAttribute("userName", payload.get("userName"));
                String phone = (String) payload.get("phone");
                model.addAttribute("phone", phone);

                // 顺手算一下这个手机号的验证码冷却还剩多久。
                // 不算的话，注册失败回跳后按钮看起来可点，用户一点却被告知
                // "验证码获取频率太高" —— 在用户眼里就是个 bug
                model.addAttribute("smsCooldownSeconds", remainingCooldownSeconds(phone));
            }
        }

        return "reg";
    }

    /**
     * 某个手机号的验证码冷却还剩多少秒（不在冷却期返回 0）
     *
     * <p>Redis 里存的是 "验证码_发送时间"，用当前时间减一下就知道隔了多久 ——
     * 判断逻辑与 {@link #sendCode} 里的防刷完全一致，两边别写岔了。
     */
    private int remainingCooldownSeconds(String phone) {
        if (StringUtils.isEmpty(phone)) {
            return 0;
        }
        String redisCode = stringRedisTemplate.opsForValue()
                .get(AuthServerConstant.SMS_CODE_CACHE_PREFIX + phone);
        if (StringUtils.isEmpty(redisCode)) {
            return 0;
        }
        long lastSendTime = Long.parseLong(redisCode.split("_")[1]);
        long elapsed = System.currentTimeMillis() - lastSendTime;
        if (elapsed >= SEND_INTERVAL_MS) {
            return 0;
        }
        return (int) ((SEND_INTERVAL_MS - elapsed) / 1000);
    }

    /**
     * 获取短信验证码
     *
     * <p>注意：<b>用户只输入手机号</b>，验证码由本服务生成后发给用户，
     * 用户从短信里看到再填回来。
     */
    @ResponseBody
    @GetMapping(value = "/sms/sendCode")
    public R sendCode(@RequestParam("phone") String phone) {

        // 1、接口防刷：同一个手机号 5 分钟内只能发一次
        String redisCode = stringRedisTemplate.opsForValue()
                .get(AuthServerConstant.SMS_CODE_CACHE_PREFIX + phone);
        if (StringUtils.isNotEmpty(redisCode)) {
            // 缓存里存的是 "验证码_发送时间"，用当前时间减一下就知道隔了多久
            long lastSendTime = Long.parseLong(redisCode.split("_")[1]);
            if (System.currentTimeMillis() - lastSendTime < SEND_INTERVAL_MS) {
                return R.error(BizCodeEnum.SMS_CODE_EXCEPTION.getCode(),
                        BizCodeEnum.SMS_CODE_EXCEPTION.getMsg());
            }
        }

        // 2、生成 6 位验证码（服务端生成，用户不需要输入）
        String codeNum = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));

        // 3、先发短信、再写缓存 —— 顺序不能反：
        //    若先写缓存，一旦发送失败，用户收不到码却要干等 5 分钟才能重发
        String response;
        try {
            // ⚠️ Feign 是【同步】调用 —— 这一行返回时结果已经拿到了，不需要额外等待
            //    （和 WebFlux 的 Mono 不同，那个才需要 block/订阅）
            response = smsFeignClient.send(phone, codeNum);

            // 测试用
//            log.info("测试用验证码是 {}", codeNum);
//            response = """
//                    {
//                      "status": "OK",
//                      "request_id": "TIDa82d09bfd9dd46e5b4c6c103701aad8a"
//                    }
//                    """;
        } catch (Exception e) {
            log.error("调用短信服务失败，phone={}", phone, e);
            return R.error(BizCodeEnum.SMS_SEND_EXCEPTION.getCode(),
                    BizCodeEnum.SMS_SEND_EXCEPTION.getMsg());
        }

        // 4、第三方把阿里云的原始报文透传回来了，成功时形如 {"status":"OK", ...}
        //    它也可能返回 HTTP 200 但内容是错误，所以要看内容、不能只看状态码
        if (StringUtils.isEmpty(response) || !response.contains("\"status\":\"OK\"")) {
            log.error("短信发送失败，phone={}，第三方响应={}", phone, response);
            return R.error(BizCodeEnum.SMS_SEND_EXCEPTION.getCode(),
                    BizCodeEnum.SMS_SEND_EXCEPTION.getMsg());
        }

        // 5、发送成功才写缓存：值为 "验证码_发送时间"
        //    验证码用于后续校验，时间戳用于上面那段的 5 分钟冷却判断
        stringRedisTemplate.opsForValue().set(
                AuthServerConstant.SMS_CODE_CACHE_PREFIX + phone,
                codeNum + "_" + System.currentTimeMillis(),
                CODE_TTL_MINUTES, TimeUnit.MINUTES);

        return R.ok();
    }

    /**
     * 注册功能
     *
     * <p>任何一步失败都是「存提示 → 带 token 重定向回注册页」，不再用
     * {@code RedirectAttributes.addFlashAttribute()}（原因见 {@link #redirectWithErrors}）。
     *
     * @param userRegisterVo 注册页用户信息
     * @param result         参数校验结果
     * @return 成功跳登录页，失败跳回注册页
     */
    @PostMapping("/regist")
    public String regist(@Valid UserRegisterVo userRegisterVo, BindingResult result) {

        // 1、校验参数（也可以写全局异常处理）
        if (result.hasErrors()) {
            Map<String, String> errorMap = result.getFieldErrors().stream().collect(Collectors.toMap(
                    FieldError::getField,
                    fieldError -> fieldError.getDefaultMessage() != null
                            ? fieldError.getDefaultMessage()
                            : "存在参数校验失败，请检查！",
                    // ⚠️ 合并函数：同一个字段可能有多条校验错误，不能省。
                    //    典型场景 —— 手机号为空时，@NotEmpty 和 @Pattern 会【同时】报错：
                    //        phone -> "手机号不能为空"
                    //        phone -> "手机号格式不正确"
                    //    这里用 "&" 把多条错误拼起来，信息一条都不丢。
                    (existing, replacement) -> existing + "&" + replacement
            ));
            return redirectWithErrors(userRegisterVo, errorMap);
        }

        // 2、两次密码必须一致。
        //    前端也可以先比一遍给用户即时反馈，但后端这一道【必须有】——
        //    请求可以绕过页面直接构造，前端校验只是体验，不是防线
        if (!userRegisterVo.getPassword().equals(userRegisterVo.getPassword2())) {
            return redirectWithErrors(userRegisterVo, Map.of("password2", "两次输入的密码不一致"));
        }

        // 3、校验验证码
        String codeKey = AuthServerConstant.SMS_CODE_CACHE_PREFIX + userRegisterVo.getPhone();
        String codeValue = stringRedisTemplate.opsForValue().get(codeKey);
        if (StringUtils.isEmpty(codeValue)) {
            return redirectWithErrors(userRegisterVo, Map.of("code", "验证码不存在或已过期"));
        }

        String code = codeValue.split("_")[0];
        if (!code.equals(userRegisterVo.getCode())) {
            return redirectWithErrors(userRegisterVo, Map.of("code", "验证码不匹配"));
        }

        // 3、调用会员服务进行注册
        R registResult = memberFeignClient.regist(userRegisterVo);
        if (registResult.getCode() != 0) {
            return redirectWithErrors(userRegisterVo, Map.of("msg", registResult.get("msg").toString()));
        }

        // 4、注册成功才删除验证码。
        //    删在前面的话，注册失败（比如用户名重复）时验证码已经没了，
        //    用户改完用户名重试会直接撞上"验证码不存在或已过期"，还得重新发短信
        stringRedisTemplate.delete(codeKey);

        // 注册成功回到登录页
        return "redirect:" + authUrl + "/login.html";
    }

    /**
     * 注册失败：把「错误提示 + 要回填的用户输入」存进 Redis，再带着一次性 token 重定向回注册页
     *
     * <p><b>为什么不用 {@code RedirectAttributes.addFlashAttribute()}？</b>
     * 那是把数据塞进<b>会话</b>里，由框架在重定向后取出并清除 —— 数据存在哪儿、
     * 什么时候被取走、被谁取走，对业务代码都是不透明的；一旦会话机制变动
     * （比如换成不共享的本地会话），行为就跟着变。
     *
     * <p>这里改成显式的「Redis + 一次性 token」：URL 上传的只是 token，内容存服务端，
     * 天然分布式友好、不受 cookie 限制；{@code getAndDelete} 保证"取一次就删"；
     * TTL（{@value #REG_ERROR_TTL_MINUTES} 分钟）兜底，用户不回来也不会残留。
     *
     * <p><b>安全性上有三点把握：</b>
     * <ol>
     *   <li>payload 里<b>只放需要回填的非敏感字段</b>（用户名、手机号）——
     *       密码类字段一个都不放，用户密码连 Redis 都不进</li>
     *   <li>URL 上只有随机 UUID，猜不到；内容在服务端，不在浏览器历史/日志里</li>
     *   <li>回填走 Thymeleaf 的 {@code th:value}，默认做 HTML 转义 ——
     *       即使用户名里塞了 {@code "><script>} 也只会被当成普通文本显示</li>
     * </ol>
     *
     * @param vo       刚才提交的表单（用于挑出要回填的字段）
     * @param errorMap 字段名 -> 错误提示
     */
    private String redirectWithErrors(UserRegisterVo vo, Map<String, String> errorMap) {
        String token = UUID.randomUUID().toString().replace("-", "");

        // ⚠️ 只挑【需要回填的字段】，绝不能图省事把整个 UserRegisterVo 塞进去 ——
        //    那里面带着 password / password2 的【明文】，等于把用户密码写进 Redis
        //    和可能被打进日志的序列化内容里
        Map<String, Object> payload = new HashMap<>();
        payload.put("errors", errorMap);
        payload.put("userName", vo.getUserName());
        payload.put("phone", vo.getPhone());

        stringRedisTemplate.opsForValue().set(
                REG_ERROR_CACHE_PREFIX + token,
                JSON.toJSONString(payload),
                REG_ERROR_TTL_MINUTES, TimeUnit.MINUTES);

        return "redirect:" + authUrl + "/reg.html?errors=" + token;
    }

}
