package com.atguigu.gulimall.thirdparty.controller;

import com.atguigu.gulimall.thirdparty.service.SmsInterface;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 短信接口
 *
 * <p>供其它微服务（如 auth-server 发注册验证码）通过 Feign 调用。
 * <p>
 * ⚠️ 这个接口<b>不要直接暴露给前端</b> —— 没有鉴权、没有限流，会被刷。
 * 正确链路：前端 → auth-server（做图形验证码 + 频率限制）→ Feign → 本接口。
 */
@RestController
@RequestMapping("/sms")
public class SmsController {

    @Autowired
    private SmsInterface smsInterface;

    /**
     * 报备的专属模板 ID（调期用云市场给的 CST_ptdie100）
     */
    @Value("${aliyun.apis.sms.template-id}")
    private String templateId;

    /**
     * 发送短信验证码
     *
     * <p>⚠️ <b>{@code code} 不是用户输入的</b>，而是<b>调用方（auth-server）自己生成的随机码</b>。
     * 本接口只负责「把码发出去」，<b>不生成、不存储、不校验</b>。
     *
     * <p>完整链路：
     * <pre>
     *   前端只填手机号
     *     → auth-server：随机生成 6 位码 + 存 Redis（key=手机号, TTL=5分钟）
     *     → Feign 调本接口(phone, code)
     *     → 阿里云发短信
     *   用户收到短信后把码填回页面
     *     → auth-server：拿用户填的码和 Redis 里的比对
     * </pre>
     *
     * @param phone 接收手机号
     * @param code  <b>服务端生成的</b>验证码（不是用户输入的）
     * @return 云市场原始响应（成功时含 "status":"OK"）
     */
    @GetMapping("/send")
    public Mono<String> send(@RequestParam("phone") String phone,
                             @RequestParam("code") String code) {

        // 云市场要求这三个字段放在【请求体的表单】里，
        // 写成 @RequestParam 会被当成 URL 查询参数 → 阿里云收到空表单 → 400
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("content", "code:" + code);   // 云市场约定的格式就是 "code:1234"
        form.add("template_id", templateId);
        form.add("phone_number", phone);

        return smsInterface.sendSms(form);
    }

}
