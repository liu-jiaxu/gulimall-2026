package com.atguigu.gulimall.auth_server.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * @ClassName: SmsFeignClient
 * @Package: com.atguigu.gulimall.auth_server.feign
 * @Description: 调用 gulimall-third-party 发短信
 * @Author: 刘家旭
 * @Create: 2026/9/11 - 19:33
 * @Version: v1.0
 */
@FeignClient("gulimall-third-party")
public interface SmsFeignClient {

    /**
     * 发送短信验证码
     *
     * <p>⚠️ <b>返回类型不能写 {@code Mono<String>}</b>：
     * Feign 是【同步】客户端，拿到响应后交给 Jackson 反序列化成声明的返回类型；
     * 而 {@code Mono} 是 Reactor 的内部类型，Jackson 构造不出来 —— 调用时会直接报反序列化失败。
     * <p>
     * 返回类型用普通类型（String / R / 自定义 DTO）即可，<b>调用方拿到的就是最终结果，不需要再等</b>。
     *
     * @param phone 手机号
     * @param code  要发送的验证码（由 <b>auth-server 自己生成</b>，不是用户输入的）
     * @return 第三方返回的原始报文，成功时形如 {@code {"status":"OK", ...}}
     */
    @GetMapping("/sms/send")
    String send(@RequestParam("phone") String phone, @RequestParam("code") String code);

}
