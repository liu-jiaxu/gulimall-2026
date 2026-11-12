package com.atguigu.gulimall.search.web;

import com.atguigu.gulimall.api.order.vo.OrderItemVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.search.feign.CartFeignClient;
import com.atguigu.gulimall.search.service.MallSearchService;
import com.atguigu.gulimall.search.vo.SearchParam;
import com.atguigu.gulimall.search.vo.SearchResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * @ClassName: SearchController
 * @Package: com.atguigu.gulimall.search.web
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/23 - 10:57
 * @Version: v1.0
 */
@Controller
public class SearchController {

    @Autowired
    private MallSearchService mallSearchService;

    /**
     * 商城前台首页地址，值来自 application.yaml 的 gulimall.shop-home-url
     * <p>
     * ⚠️ 为什么不直接在模板里写 ${@environment.getProperty(...)}：
     * Thymeleaf 3.1 出于防 SpEL 注入的安全考虑，默认禁止在模板表达式里
     * 引用 bean（@beanName 语法）、实例化对象（new）和访问静态类（T(...)）。
     * 写成那样会在渲染时直接抛 TemplateProcessingException：
     * "Instantiation of new objects and access to static classes or parameters is forbidden"，
     * 表现为整个页面 500。
     * <p>
     * 所以值必须由 Controller 放进 Model，模板只负责展示。
     * 这样也更规范：模板里不出现任何"取数据"的逻辑。
     */

    @Autowired
    private CartFeignClient cartFeignClient;

    @GetMapping({"/", "/list.html"})
    public String listPage(SearchParam searchParam, Model model, HttpServletRequest request) {

        // 获取所有查询条件，便于面包屑清空
        searchParam.set_queryString(request.getQueryString());
        SearchResult result = mallSearchService.search(searchParam);

        model.addAttribute("result", result);
        List<OrderItemVo> cartItems = cartItems();
        model.addAttribute("cartItems", cartItems);
        model.addAttribute("cartCount", cartItems.size());

        return "list";      // → templates/list.html
    }

    /**
     * 购物车商品明细：购物车服务挂了也不能让搜索页打不开，失败就当空车
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
