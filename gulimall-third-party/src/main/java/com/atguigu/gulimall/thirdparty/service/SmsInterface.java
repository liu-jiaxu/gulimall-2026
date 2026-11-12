package com.atguigu.gulimall.thirdparty.service;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;

public interface SmsInterface {

    /**
     * 发送短信
     *
     * @param form 表单参数，包含：
     *             <ul>
     *                 <li>{@code phone}：手机号</li>
     *                 <li>{@code sign}：签名</li>
     *                 <li>{@code template}：模板</li>
     *                 <li>{@code param}：模板参数，JSON字符串</li>
     *             </ul>
     * @return 云市场返回的报文，成功时形如 {@code {"status":"OK", ...}}
     */
    @PostExchange(
            url = "/data/send_sms",
            contentType = "application/x-www-form-urlencoded; charset=UTF-8")
    Mono<String> sendSms(@RequestBody MultiValueMap<String, String> form);

}
