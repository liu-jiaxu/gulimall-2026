package com.atguigu.gulimall.seckill.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.seckill.service.SeckillSkuService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀商品查询
 */
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    @Autowired
    private SeckillSkuService seckillSkuService;

    /**
     * 可展示的秒杀场次
     */
    @GetMapping("/sessions")
    public R sessions() {
        // 1. 查未来 3 天 + 进行中的启用场次
        return R.ok().put("sessions", seckillSkuService.listActiveSessions());
    }

    /**
     * 某场次的秒杀商品
     */
    @GetMapping("/skus")
    public R skus(@RequestParam("sessionId") Long sessionId) {
        // 1. 从 Redis hash 读商品信息
        return R.ok().put("skus", seckillSkuService.getSessionSkus(sessionId));
    }

    /**
     * 首页秒杀区：优先最近进行中的场次
     */
    @GetMapping("/home")
    public R home() {
        // 1. 取首页要展示的秒杀场次和商品，优先选最近正在进行的场次
        return R.ok().put("seckillHome", seckillSkuService.getHome());
    }

    /**
     * 商品详情页秒杀模式：价格、限购、已购、剩余。
     * 允许游客访问，登录时才会带出已购数量。
     */
    @SentinelResource(value = "seckillSkuInfo",
            blockHandler = "skuInfoBlocked", fallback = "skuInfoFallback")
    @GetMapping("/sku/info")
    public R skuInfo(@RequestParam("sessionId") Long sessionId,
                     @RequestParam("skuId") Long skuId,
                     HttpSession session) {
        // 1. 游客 memberId 为空，已购按 0
        MemberResponseVo member = session == null ? null
                : (MemberResponseVo) session.getAttribute(AuthServerConstant.LOGIN_USER);
        Long memberId = member == null ? null : member.getId();
        // 2. 把会员信息带进查询，登录用户能算出已购数量，游客已购按 0 处理
        return R.ok().put("seckillSkuInfo",
                seckillSkuService.getSkuInfo(sessionId, skuId, memberId));
    }

    /**
     * 热点参数/流控被拦时：返回统一错误码
     */
    public R skuInfoBlocked(Long sessionId, Long skuId, HttpSession session, BlockException ex) {
        // 1. 被 Sentinel 限流时返回统一的限流错误码，前端按普通业务提示让用户稍后再试
        return R.error(com.atguigu.gulimall.common.enums.BizCodeEnum.SECKILL_FLOW_LIMIT.getCode(),
                com.atguigu.gulimall.common.enums.BizCodeEnum.SECKILL_FLOW_LIMIT.getMsg());
    }

    /**
     * 商品查询异常兜底：不把 500 抛给前端
     */
    public R skuInfoFallback(Long sessionId, Long skuId, HttpSession session, Throwable t) {
        // 1. 查询过程抛出异常时兜底返回友好错误，不让 500 直接落到商品详情页
        return R.error(com.atguigu.gulimall.common.enums.BizCodeEnum.SECKILL_PARAM_INVALID.getCode(),
                "秒杀商品信息查询失败，请稍后再试");
    }

    /**
     * 手动触发上架（测试/运维用；正常靠每天 3 点定时任务）
     */
    @PostMapping("/upload")
    public R upload() {
        // 1. 立即上架未来 3 天 + 进行中的场次商品
        seckillSkuService.uploadSeckillSkuLatest3Days();
        // 2. 上架完成后返回成功，调用方只需知道这次触发有没有报错
        return R.ok();
    }

    /**
     * 关联新增/修改后刷新单个商品信息（coupon 服务内部调用）
     */
    @PostMapping("/refresh")
    public R refresh(@RequestParam("sessionId") Long sessionId,
                     @RequestParam("skuId") Long skuId) {
        // 1. 按数据库最新关联重建这个 sku 的 hash
        seckillSkuService.refreshSessionSku(sessionId, skuId);
        // 2. 刷新完成后返回成功，coupon 服务据此确认缓存已经更新
        return R.ok();
    }
}
