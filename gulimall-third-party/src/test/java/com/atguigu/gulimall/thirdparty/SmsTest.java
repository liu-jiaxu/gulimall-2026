package com.atguigu.gulimall.thirdparty;

import com.atguigu.gulimall.thirdparty.service.SmsInterface;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 短信接口测试
 *
 * <p><b>调用方式与 SmsController 完全一致</b> —— 都是注入 {@link SmsInterface}
 * 然后 {@code sendSms(form)}，区别只在最后一步：
 * <pre>
 *   Controller： return smsInterface.sendSms(form);        ← 直接返回 Mono，Spring MVC 自己会等
 *   本测试：     mono.block(...)                            ← 测试要主动等，否则方法先结束、什么都看不到
 * </pre>
 */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false"})
public class SmsTest {

    /** 和 Controller 注入的是同一个 bean */
    @Autowired
    private SmsInterface smsInterface;

    @Value("${aliyun.apis.sms.template-id}")
    private String templateId;

    @Test
    void testSendSms() {
        String phone = "15866718620";
        String code = "1234";      // 生产环境这个值由 auth-server 生成

        // ↓↓↓ 这段和 SmsController.send() 里一模一样 ↓↓↓
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("content", "code:" + code);
        form.add("template_id", templateId);
        form.add("phone_number", phone);

        Mono<String> mono = smsInterface.sendSms(form);
        // ↑↑↑ 这段和 SmsController.send() 里一模一样 ↑↑↑

        // 测试特有的最后一步：主动等结果。
        // Controller 不需要这一步 —— Spring MVC 会等 Mono 完成再把响应写回去。
        // 用 block() 而不是 subscribe()：subscribe 是异步的，方法会先结束，还会把异常吞掉。
        System.out.println("短信发送响应: " + mono.block(Duration.ofSeconds(10)));
    }

}
