package com.atguigu.gulimall.common.web;

import com.atguigu.gulimall.common.region.RegionCatalog;
import com.atguigu.gulimall.common.region.RegionNode;
import com.atguigu.gulimall.common.utils.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 省市区动态数据接口（同源，所有 Servlet 模块自动注册）。
 *
 * <p>页面上的地址下拉框不再写死，统一从这里取。
 * 数据来自 classpath 的 region/pca-code.json，进程内缓存。
 */
@RestController
public class RegionController {

    @GetMapping("/region/provinces")
    public R provinces() {
        return R.ok().put("data", options(RegionCatalog.provinces()));
    }

    @GetMapping("/region/cities")
    public R cities(@RequestParam("provinceCode") String provinceCode) {
        return R.ok().put("data", options(RegionCatalog.cities(provinceCode)));
    }

    @GetMapping("/region/areas")
    public R areas(@RequestParam("cityCode") String cityCode) {
        return R.ok().put("data", options(RegionCatalog.areas(cityCode)));
    }

    /** 只返回 code/name，不把整棵子树塞给前端 */
    private List<Map<String, String>> options(List<RegionNode> nodes) {
        return nodes.stream().map(node -> {
            Map<String, String> option = new LinkedHashMap<>();
            option.put("code", node.getCode());
            option.put("name", node.getName());
            return option;
        }).toList();
    }
}
