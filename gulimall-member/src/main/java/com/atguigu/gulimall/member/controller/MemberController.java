package com.atguigu.gulimall.member.controller;

import java.util.Arrays;
import java.util.Map;

import com.atguigu.gulimall.api.auth_server.UserLoginVo;
import com.atguigu.gulimall.api.auth_server.UserRegisterVo;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.member.feign.CouponFeignClient;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.atguigu.gulimall.member.entity.MemberEntity;
import com.atguigu.gulimall.member.service.MemberService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;



/**
 * 会员
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 19:55:54
 */
@RestController
@RequestMapping("member/member")
public class MemberController {
    @Autowired
    private MemberService memberService;

    @Autowired
    private CouponFeignClient couponFeignClient;

    /**
     * 获取当前会员的优惠券列表
     * <p>
     * 通过 OpenFeign 远程调用 coupon 服务（gulimall-coupon）的
     * {@code /coupon/coupon/member/list} 接口，获取当前会员的优惠券列表，
     * 连同会员信息一起返回。
     * <p>
     * <a href="http://localhost:8000/member/member/coupon/list">http://localhost:8000/member/member/coupon/list</a>
     *
     * @return 包含会员信息和优惠券列表的响应对象
     */
    @RequestMapping("/coupon/list")
    public R getCouponList() {
        // 实际业务和远程调用最好下沉到service层实装
        MemberEntity memberEntity = new MemberEntity();
        memberEntity.setNickname("张三");
        R memberCoupons = couponFeignClient.memberCoupons();
        return R.ok().put("member", memberEntity).put("coupons", memberCoupons.get("coupons"));
    }

    /**
     * 社交登录（GitHub / 微信共用）
     *
     * <p>只接收授权码和渠道标识；换社交用户、查/建会员都在 service 内部完成，
     * 免得把各平台的细节漏到调用方（auth-server）去。
     *
     * @param code   社交平台回调带回来的授权码
     * @param source 渠道标识：github / wechat，默认 github（兼容老调用）
     * @return 登录成功的会员信息（放在 data 里）
     */
    @PostMapping("/oauth2/login")
    public R oauthLogin(@RequestParam("code") String code,
                        @RequestParam(value = "source", defaultValue = "github") String source) {
        try {
            MemberEntity member = memberService.oauthLogin(code, source);
            return R.ok().put("data", member);
        } catch (RRException e) {
            return R.error(e.getCode(), e.getMsg());
        }
    }

    /**
     * 会员登录
     *
     * @param vo 用户登录信息封装对象
     * @return 成功时 {@code data} 里是会员信息（auth-server 拿它写 session）
     */
    @PostMapping("/login")
    public R login(@RequestBody UserLoginVo vo) {
        try {
            MemberEntity member = memberService.login(vo);
            return R.ok().put("data", member);
        } catch (RRException e) {
            return R.error(e.getCode(), e.getMsg());
        }
    }

    /**
     * 注册会员
     *
     * @param vo 用户注册信息封装对象
     * @return 注册结果响应对象
     */
    @PostMapping("/regist")
    public R regist(@RequestBody UserRegisterVo vo) {
        try {
            memberService.regist(vo);
        } catch (RRException e) {
            return R.error(e.getCode(), e.getMsg());
        }
        return R.ok();
    }

    /**
     * 列表
     */
    @RequestMapping("/list")
    @RequiresPermissions("member:member:list")
    public R list(@RequestParam Map<String, Object> params){
        PageUtils page = memberService.queryPage(params);

        return R.ok().put("page", page);
    }


    /**
     * 信息
     */
    @RequestMapping("/info/{id}")
    @RequiresPermissions("member:member:info")
    public R info(@PathVariable("id") Long id){
		MemberEntity member = memberService.getById(id);

        return R.ok().put("member", member);
    }

    /**
     * 保存
     */
    @RequestMapping("/save")
    @RequiresPermissions("member:member:save")
    public R save(@RequestBody MemberEntity member){
		memberService.save(member);

        return R.ok();
    }

    /**
     * 修改
     */
    @RequestMapping("/update")
    @RequiresPermissions("member:member:update")
    public R update(@RequestBody MemberEntity member){
		memberService.updateById(member);

        return R.ok();
    }

    /**
     * 删除
     */
    @RequestMapping("/delete")
    @RequiresPermissions("member:member:delete")
    public R delete(@RequestBody Long[] ids){
		memberService.removeByIds(Arrays.asList(ids));

        return R.ok();
    }

}
