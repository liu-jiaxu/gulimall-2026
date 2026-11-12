package com.atguigu.gulimall.cart.web;

import com.atguigu.gulimall.api.cart.CartItemVo;
import com.atguigu.gulimall.api.cart.CartVo;
import com.atguigu.gulimall.api.cart.UserInfoTo;
import com.atguigu.gulimall.cart.service.CartService;
import com.atguigu.gulimall.cart.thread.UserThreadLocal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * @ClassName: CartController
 * @Package: com.atguigu.gulimall.cart.web
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/7 - 11:30
 * @Version: v1.0
 */
@Controller
public class CartController {

    /**
     * 获取当前用户的购物车商品项
     * @return
     */
    @GetMapping(value = "/currentUserCartItems")
    @ResponseBody
    public List<CartItemVo> getCurrentCartItems() {
        return cartService.getUserCartItems();
    }

    /** 加购/改数量后重定向回购物车页要用 */
    @Value("${gulimall.cart-url}")
    private String cartUrl;

    @Autowired
    private CartService cartService;

    @GetMapping({"/", "/index.html"})
    public String cart() {
        return "index";
    }

    /**
     * 跳转到购物车列表
     * @param model model
     * @return 购物车列表
     */
    @GetMapping("/cart.html")
    public String cartListPage(Model model) {
        CartVo cartVo = cartService.getCart();
        model.addAttribute("cart", cartVo);
        return "cartList";
    }

    /**
     * 添加商品到购物车
     *
     * @param skuId skuId
     * @param num   数量
     * @return 加购成功页（index）
     */
    @GetMapping("/addToCart")
    public String addToCart(@RequestParam("skuId") Long skuId,
                            @RequestParam(value = "num", defaultValue = "1") Integer num,
                            RedirectAttributes redirectAttributes) {
        cartService.addToCart(skuId, num);
        redirectAttributes.addAttribute("skuId", skuId);
        return "redirect:" + cartUrl + "/addToCartSuccessPage.html";
    }

    /**
     * 跳转到添加购物车成功页面
     * @param skuId
     * @param model
     * @return
     */
    @GetMapping(value = "/addToCartSuccessPage.html")
    public String addToCartSuccessPage(@RequestParam("skuId") Long skuId, Model model) {
        //重定向到成功页面再查询购物车数据，防止每次刷新都查询添加购物车
        CartItemVo cartItemVo = cartService.getCartItem(skuId);
        model.addAttribute("item",cartItemVo);
        return "index";
    }

    /**
     * 商品是否选中
     * @param skuId
     * @param checked
     * @return
     */
    @GetMapping(value = "/checkItem")
    public String checkItem(@RequestParam(value = "skuId") Long skuId,
                            @RequestParam(value = "checked") Integer checked) {
        cartService.checkItem(skuId,checked);
        return "redirect:" + cartUrl + "/cart.html";
    }

    /**
     * 全选 / 取消全选
     * @param checked 1 全选，0 取消全选
     * @return 重定向回购物车列表（刷新不会重复提交）
     */
    @GetMapping(value = "/checkAll")
    public String checkAll(@RequestParam(value = "checked") Integer checked) {
        cartService.checkAll(checked);
        return "redirect:" + cartUrl + "/cart.html";
    }

    /**
     * 改变商品数量
     *
     * <p>JMeter测试：1s200请求
     * <p>为保证原子操作加Redisson锁，因此单个用户多次请求可以看做是串行的，但实际1s内用户请求数量很少，加锁导致的几ms延迟用户基本无感知
     * <p>重定向
     * <p><img src="doc-files/修改商品数量-重定向.png" alt="加购流程图" width="720">
     * <p>不重定向
     * <p><img src="doc-files/修改商品数量-不重定向.png" alt="加购流程图" width="720">
     *
     * @param skuId skuId
     * @param num 数量
     * @return 重定向至购物车
     */
    @GetMapping(value = "/countItem")
    public String countItem(@RequestParam(value = "skuId") Long skuId,
                            @RequestParam(value = "num") Integer num) {
        cartService.changeItemCount(skuId,num);
        return "redirect:" + cartUrl + "/cart.html";
    }


    /**
     * 删除商品信息
     * @param skuId
     * @return
     */
    @GetMapping(value = "/deleteItem")
    public String deleteItem(@RequestParam("skuId") Integer skuId) {
        cartService.deleteIdCartInfo(skuId);
        return "redirect:" + cartUrl + "/cart.html";
    }

    /**
     * 批量删除购物项（删除选中的商品）
     *
     * @param skuIds 要删除的 skuId，URL 上用逗号分隔，如 skuIds=1,2,3
     * @return 重定向回购物车列表
     */
    @GetMapping(value = "/deleteCheckedItems")
    public String deleteCheckedItems(@RequestParam(value = "skuIds") List<Integer> skuIds) {
        cartService.deleteCartItems(skuIds);
        return "redirect:" + cartUrl + "/cart.html";
    }

    /**
     * 清除下柜商品：查库存，库存为 0 的购物项直接从购物车里删掉
     *
     * @return 重定向回购物车列表
     */
    @GetMapping(value = "/clearNoStockItems")
    public String clearNoStockItems() {
        cartService.clearNoStockItems();
        return "redirect:" + cartUrl + "/cart.html";
    }

    /**
     * 购物车商品类型数量：给商品详情页、首页、搜索页的"购物车"提示用
     *
     * @return 购物车商品类型数量
     */
    @ResponseBody
    @GetMapping(value = "/cartCount")
    public Long cartCount() {
        return cartService.getCartCountType();
    }

}
