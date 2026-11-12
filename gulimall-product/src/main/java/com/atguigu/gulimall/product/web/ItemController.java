package com.atguigu.gulimall.product.web;

import com.atguigu.gulimall.api.order.vo.OrderItemVo;
import com.atguigu.gulimall.api.seckill.vo.SeckillSkuInfoVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.product.feign.CartFeignClient;
import com.atguigu.gulimall.product.feign.SeckillFeignClient;
import com.atguigu.gulimall.product.service.SkuInfoService;
import com.atguigu.gulimall.product.vo.SkuItemVo;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * @ClassName: ItemController
 * @Package: com.atguigu.gulimall.product.web
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/28 - 21:44
 * @Version: v1.0
 */
@Controller
public class ItemController {


    @Autowired
    private SkuInfoService skuInfoService;
    @Autowired
    private CartFeignClient cartFeignClient;
    @Autowired
    private SeckillFeignClient seckillFeignClient;

    @Value("${gulimall.seckill-url}")
    private String seckillUrl;
    @Value("${gulimall.auth-url}")
    private String authUrl;
    @Value("${gulimall.order-url}")
    private String orderUrl;

    /**
     * 展示当前sku详情
     *
     * @param skuId   skuId
     * @param model   model
     * @return sku信息
     */
    @GetMapping("/item.html")
    public String skuItem(@RequestParam(value = "skuId", required = false) Long skuId,
                          @RequestParam(value = "seckillSessionId", required = false) Long seckillSessionId,
                          HttpSession session,
                          Model model) {

        SkuItemVo skuItemVo = skuInfoService.item(skuId);
        model.addAttribute("item", skuItemVo);
        List<OrderItemVo> cartItems = cartItems();
        model.addAttribute("cartItems", cartItems);
        model.addAttribute("cartCount", cartItems.size());

        // 1. 从秒杀页跳过来时，价格/限购/已购都由 seckill 提供，不信任 URL 参数
        SeckillSkuInfoVo seckillSku = seckillSessionId == null
                ? null : loadSeckillSku(seckillSessionId, skuId);
        int seckillMaxQuantity = 0;
        if (seckillSku != null) {
            seckillMaxQuantity = Math.min(
                    Math.max(seckillSku.getLimit() - seckillSku.getBought(), 0),
                    Math.max(seckillSku.getStock(), 0));
        }
        model.addAttribute("seckillSku", seckillSku);
        model.addAttribute("seckillMaxQuantity", seckillMaxQuantity);
        model.addAttribute("seckillUrl", seckillUrl);
        model.addAttribute("authUrl", authUrl);
        model.addAttribute("orderUrl", orderUrl);
        model.addAttribute("loginUser", session == null ? null
                : session.getAttribute(AuthServerConstant.LOGIN_USER));

        return "item";
    }

    /**
     * 商品详情页秒杀模式：seckill 不可用时退化成普通商品页
     */
    private SeckillSkuInfoVo loadSeckillSku(Long sessionId, Long skuId) {
        try {
            R result = seckillFeignClient.getSkuInfo(sessionId, skuId);
            if (result == null || !Integer.valueOf(0).equals(result.getCode())) {
                return null;
            }
            return result.getData("seckillSkuInfo", new TypeReference<SeckillSkuInfoVo>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 购物车商品明细：购物车服务挂了也不能让商品页打不开，失败就当空车
     */
    private List<OrderItemVo> cartItems() {
        try {
            List<OrderItemVo> items = cartFeignClient.getCurrentCartItems();
            return items == null ? List.of() : items;
        } catch (Exception e) {
            return List.of();
        }
    }

}
