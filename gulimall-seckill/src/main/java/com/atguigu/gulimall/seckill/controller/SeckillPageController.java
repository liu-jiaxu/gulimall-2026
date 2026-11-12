package com.atguigu.gulimall.seckill.controller;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.api.order.vo.MemberAddressVo;
import com.atguigu.gulimall.api.seckill.vo.SeckillHomeVo;
import com.atguigu.gulimall.api.seckill.vo.SeckillSkuInfoVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.seckill.feign.MemberFeignClient;
import com.atguigu.gulimall.seckill.service.SeckillSkuService;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 秒杀前台页面：场次/商品列表、下单确认页。
 */
@Slf4j
@Controller
public class SeckillPageController {

    @Autowired
    private SeckillSkuService seckillSkuService;

    @Autowired
    private MemberFeignClient memberFeignClient;

    @Value("${gulimall.item-url}")
    private String itemUrl;

    @Value("${gulimall.seckill-url}")
    private String seckillUrl;

    @Value("${gulimall.auth-url}")
    private String authUrl;

    @Value("${gulimall.order-url}")
    private String orderUrl;

    @Value("${gulimall.shop-home-url}")
    private String shopHomeUrl;

    @Value("${gulimall.member-url}")
    private String memberUrl;

    /**
     * 秒杀页：展示所有可展示场次和商品
     */
    @GetMapping({"/", "/seckill.html"})
    public String seckillPage(HttpSession session, Model model) {
        // 1. 查出所有可展示的场次和场次内商品，供页面渲染
        List<SeckillHomeVo> sessions = seckillSkuService.listSessionsWithSkus();

        // 2. 从会话里取登录会员，取不到就当游客，页面顶部导航据此决定显示登录还是昵称
        // 顶部导航需要：返回主页 + 登录状态
        MemberResponseVo member = session == null ? null
                : (MemberResponseVo) session.getAttribute(AuthServerConstant.LOGIN_USER);

        // 3. 把场次数据和各个服务地址塞进模板，页面跳转链接不用写死域名
        model.addAttribute("sessions", sessions);
        model.addAttribute("itemUrl", itemUrl);
        model.addAttribute("authUrl", authUrl);
        model.addAttribute("shopHomeUrl", shopHomeUrl);
        model.addAttribute("loginUser", member);
        // 4. 返回秒杀页模板，由 Thymeleaf 渲染成最终 HTML
        return "seckill";
    }

    /**
     * 秒杀确认页：选地址、数量，提交后轮询建单结果再跳支付页
     */
    @GetMapping("/confirm.html")
    public String confirmPage(@RequestParam("sessionId") Long sessionId,
                              @RequestParam("skuId") Long skuId,
                              @RequestParam(value = "num", defaultValue = "1") Integer num,
                              HttpSession session,
                              Model model) {
        // 1. 必须登录；未登录跳登录页，登录后回到这个确认页
        MemberResponseVo member = session == null ? null
                : (MemberResponseVo) session.getAttribute(AuthServerConstant.LOGIN_USER);
        if (member == null) {
            String returnUrl = seckillUrl + "/confirm.html?sessionId=" + sessionId
                    + "&skuId=" + skuId + "&num=" + num;
            return "redirect:" + authUrl + "/login.html?returnUrl="
                    + URLEncoder.encode(returnUrl, StandardCharsets.UTF_8);
        }

        // 2. 秒杀商品信息
        SeckillSkuInfoVo info = seckillSkuService.getSkuInfo(sessionId, skuId, member.getId());
        if (info == null) {
            return "redirect:" + seckillUrl + "/seckill.html";
        }

        // 3. 秒杀页不再让用户选地址，只用默认收货地址；没有默认地址先去个人中心设置
        MemberAddressVo defaultAddress = null;
        try {
            List<MemberAddressVo> list = memberFeignClient.getAddress(member.getId());
            if (list != null) {
                defaultAddress = list.stream()
                        .filter(address -> Integer.valueOf(1).equals(address.getDefaultStatus()))
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            log.error("[seckill-confirm] 查询默认收货地址失败 memberId={}", member.getId(), e);
        }
        if (defaultAddress == null) {
            String returnUrl = seckillUrl + "/confirm.html?sessionId=" + sessionId
                    + "&skuId=" + skuId + "&num=" + num;
            return "redirect:" + memberUrl + "/member/center.html?needAddress=1&returnUrl="
                    + URLEncoder.encode(returnUrl, StandardCharsets.UTF_8);
        }

        // 4. 数量不能超过限购剩余和库存
        int max = Math.min(
                Math.max(info.getLimit() - info.getBought(), 0),
                Math.max(info.getStock(), 0));
        int quantity = Math.max(1, Math.min(num, Math.max(max, 1)));

        // 5. 把商品信息、默认地址、校正后的数量塞进模板，确认页直接展示这些数据
        model.addAttribute("info", info);
        model.addAttribute("defaultAddress", defaultAddress);
        model.addAttribute("quantity", quantity);
        model.addAttribute("maxQuantity", max);
        model.addAttribute("orderUrl", orderUrl);
        model.addAttribute("member", member);
        // 6. 返回确认页模板，用户核对后就能提交抢购
        return "seckill-confirm";
    }
}
