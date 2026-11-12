package com.atguigu.gulimall.ware.controller;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.atguigu.gulimall.api.ware.vo.FareRequestVo;
import com.atguigu.gulimall.api.ware.vo.FareResponseVo;
import com.atguigu.gulimall.api.ware.vo.SkuShippingFromVo;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.atguigu.gulimall.ware.entity.WareInfoEntity;
import com.atguigu.gulimall.ware.service.WareInfoService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;



/**
 * 仓库信息
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:18:03
 */
@RestController
@RequestMapping("ware/wareinfo")
public class WareInfoController {
    @Autowired
    private WareInfoService wareInfoService;

    /**
     * 计算运费与时间
     *
     * <p>原来是 GET + addrId 查询参数，改成 POST + JSON body：除了收货地址，
     * 还要带一份商品清单（id / 件数 / 重量），这种嵌套结构塞不进查询串。
     *
     * <p>入参 {@code FareRequestVo} 里只保留算运费用得上的字段，
     * 完整的商品信息（图片、标题、销售属性）不往这里传。
     *
     * <p>返回的 {@code fare} 是 {@link FareResponseVo}：既含整单总运费，
     * 也含逐个商品的运输明细（从哪个仓发、多远、多久）—— 页面要按商品展示，光给总额不够用。
     */
    @PostMapping("/fare")
    public R getFare(@RequestBody FareRequestVo fareRequestVo) {
        FareResponseVo fare = wareInfoService.getFare(fareRequestVo);
        return R.ok().put("fare", fare);
    }

    /**
     * 商品详情页的“发货地”：返回该 SKU 有货的仓库和可用库存。
     */
    @PostMapping("/shippingFrom")
    public List<SkuShippingFromVo> shippingFrom(@RequestBody List<FareRequestVo.Item> items) {
        return wareInfoService.getShippingFrom(items);
    }

    /**
     * 列表
     */
    @RequestMapping("/list")
    @RequiresPermissions("ware:wareinfo:list")
    public R list(@RequestParam Map<String, Object> params){
        PageUtils page = wareInfoService.queryPageByCondition(params);

        return R.ok().put("page", page);
    }


    /**
     * 信息
     */
    @RequestMapping("/info/{id}")
    @RequiresPermissions("ware:wareinfo:info")
    public R info(@PathVariable("id") Long id){
		WareInfoEntity wareInfo = wareInfoService.getById(id);

        return R.ok().put("wareInfo", wareInfo);
    }

    /**
     * 保存
     */
    @RequestMapping("/save")
    @RequiresPermissions("ware:wareinfo:save")
    public R save(@RequestBody WareInfoEntity wareInfo){
		wareInfoService.save(wareInfo);

        return R.ok();
    }

    /**
     * 修改
     */
    @RequestMapping("/update")
    @RequiresPermissions("ware:wareinfo:update")
    public R update(@RequestBody WareInfoEntity wareInfo){
		wareInfoService.updateById(wareInfo);

        return R.ok();
    }

    /**
     * 删除
     */
    @RequestMapping("/delete")
    @RequiresPermissions("ware:wareinfo:delete")
    public R delete(@RequestBody Long[] ids){
		wareInfoService.removeByIds(Arrays.asList(ids));

        return R.ok();
    }

}
