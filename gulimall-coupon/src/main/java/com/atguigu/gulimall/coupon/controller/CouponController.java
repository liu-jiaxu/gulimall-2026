package com.atguigu.gulimall.coupon.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.alibaba.nacos.api.config.annotation.NacosValue;
import com.atguigu.gulimall.coupon.property.GulimallComponentsProperties;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.*;

import com.atguigu.gulimall.coupon.entity.CouponEntity;
import com.atguigu.gulimall.coupon.service.CouponService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;



/**
 * 优惠券信息
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 19:44:40
 */
@RestController
@RequestMapping("coupon/coupon")
public class CouponController {
    @Autowired
    private CouponService couponService;

    @Autowired
    private GulimallComponentsProperties gulimallComponentsProperties;

    /**
     * 获取nacos配置中心的配置信息
     * <p>
     * 通过访问 {@code /coupon/coupon/get/nacos/config} 接口，获取nacos配置中心的配置信息。
     * <p>
     * <a href="http://localhost:7000/coupon/coupon/get/nacos/config">http://localhost:7000/coupon/coupon/get/nacos/config</a>
     *
     * @return 包含nacos配置中心配置信息的响应对象
     */
    @GetMapping("/get/nacos/config")
    public R getNacosConfig(
            // 1.本地配置文件读取，Nacos配置源会覆盖本地的配置变量
            @Value("${user.name:#{null}}") String name, @Value("${user.password:}") String password,
            // 2.方法中使用@Value获取数据无需@RefreshScope注解，可以直接不停机修改
            @Value(value = "${user.info:default}") String info, @Value(value = "${user.addr:}") String addr
            ) {
        return R.ok().put("name", name).put("password", password)
                .put("info", info)
                .put("addr", addr)
                // 3.使用@ConfigurationProperties(prefix = "gulimall")也可以不停机修改
                .put("components", gulimallComponentsProperties);
    }

    /**
     * 获取会员优惠券列表（member服务远程调用）
     *   - @RequestMapping不指定访问方式时，get/post/put/delete都可以访问，实际开发会细分
     * @return 会员优惠券列表
     */
    @RequestMapping("/member/list")
    public R memberCoupons() {
        CouponEntity couponEntity = new CouponEntity();
        couponEntity.setCouponName("满100减10");
        return R.ok().put("coupons", List.of(couponEntity));
    }

    /**
     * 列表
     */
    @RequestMapping("/list")
    @RequiresPermissions("coupon:coupon:list")
    public R list(@RequestParam Map<String, Object> params){
        PageUtils page = couponService.queryPage(params);

        return R.ok().put("page", page);
    }


    /**
     * 信息
     */
    @RequestMapping("/info/{id}")
    @RequiresPermissions("coupon:coupon:info")
    public R info(@PathVariable("id") Long id){
		CouponEntity coupon = couponService.getById(id);

        return R.ok().put("coupon", coupon);
    }

    /**
     * 保存
     */
    @RequestMapping("/save")
    @RequiresPermissions("coupon:coupon:save")
    public R save(@RequestBody CouponEntity coupon){
		couponService.save(coupon);

        return R.ok();
    }

    /**
     * 修改
     */
    @RequestMapping("/update")
    @RequiresPermissions("coupon:coupon:update")
    public R update(@RequestBody CouponEntity coupon){
		couponService.updateById(coupon);

        return R.ok();
    }

    /**
     * 删除
     */
    @RequestMapping("/delete")
    @RequiresPermissions("coupon:coupon:delete")
    public R delete(@RequestBody Long[] ids){
		couponService.removeByIds(Arrays.asList(ids));

        return R.ok();
    }

}
