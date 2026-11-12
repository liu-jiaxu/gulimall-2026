package com.atguigu.gulimall.search.controller;

import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.common.web.InternalAiControllerSupport;
import com.atguigu.gulimall.search.service.MallSearchService;
import com.atguigu.gulimall.search.vo.SearchParam;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 给 gulimall-agent 用的 ES 商品搜索接口。
 *
 * <p>搜索页本身返回 Thymeleaf HTML，AI 需要结构化 JSON，所以单独开这个只读端点。
 */
@RestController
@RequestMapping("/internal/ai/search")
@RequiredArgsConstructor
public class InternalAiController extends InternalAiControllerSupport {

    private final MallSearchService mallSearchService;

    @GetMapping
    public R search(HttpServletRequest request,
                    @RequestParam(value = "keyword", required = false) String keyword,
                    @RequestParam(value = "catelog3Id", required = false) Long catelog3Id,
                    @RequestParam(value = "brandId", required = false) String brandId,
                    @RequestParam(value = "hasStock", required = false) Integer hasStock,
                    @RequestParam(value = "skuPrice", required = false) String skuPrice,
                    @RequestParam(value = "sort", required = false) String sort,
                    @RequestParam(value = "page", defaultValue = "1") Integer page) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 组装搜索参数
        SearchParam param = new SearchParam();
        param.setKeyword(keyword);
        param.setCatelog3Id(catelog3Id);
        param.setHasStock(hasStock);
        param.setSkuPrice(skuPrice);
        param.setSort(sort);
        param.setPageNum(page);
        if (brandId != null && !brandId.isBlank()) {
            List<Long> brands = Arrays.stream(brandId.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::valueOf)
                    .toList();
            param.setBrandId(brands);
        }

        // 3. 复用搜索服务
        return R.ok().put("data", mallSearchService.search(param));
    }
}
