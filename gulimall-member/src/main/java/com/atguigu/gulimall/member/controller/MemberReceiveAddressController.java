package com.atguigu.gulimall.member.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.servlet.http.HttpSession;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.atguigu.gulimall.member.entity.MemberReceiveAddressEntity;
import com.atguigu.gulimall.member.service.MemberReceiveAddressService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;



/**
 * 会员收货地址
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 19:55:54
 */
@RestController
@RequestMapping("member/memberreceiveaddress")
public class MemberReceiveAddressController {

    /** 默认地址的标记值 */
    private static final int DEFAULT_STATUS_YES = 1;

    @Autowired
    private MemberReceiveAddressService memberReceiveAddressService;

    /**
     * 根据id查询地址
     * @param addrId
     * @return
     */
    @GetMapping(value = "/{addrId}")
    public MemberReceiveAddressEntity getAddressById(@PathVariable Long addrId) {
        return memberReceiveAddressService.getById(addrId);
    }

    /**
     * 根据会员id查询会员的所有地址
     * @param memberId
     * @return
     */
    @GetMapping(value = "/{memberId}/address")
    public List<MemberReceiveAddressEntity> getAddress(@PathVariable Long memberId) {
        return memberReceiveAddressService.getAddress(memberId);
    }

    /**
     * 当前登录会员新增收货地址（订单确认页用）
     *
     * <p><b>memberId 不从请求体取</b>，而是从会话里的登录信息拿 —— 否则任何人都能给别人的账号塞地址。
     * 登录态由 auth-server 登录时写入 Spring Session（跨服务共享），这里直接读得到。
     *
     * <p>也<b>不加</b> {@code @RequiresPermissions}：那套是后台管理的权限体系（需要 Shiro 登录），
     * 会员端走的是登录态，加了两边都调不通。
     */
    @PostMapping("/save")
    public R saveAddress(@RequestBody MemberReceiveAddressEntity address, HttpSession session) {
        MemberResponseVo loginUser = loginUser(session);
        if (loginUser == null) {
            return notLogin();
        }

        // 置空 id：这个接口只做新增，防止前端传了 id 把它变成"改别人的地址"
        address.setId(null);
        address.setMemberId(loginUser.getId());
        memberReceiveAddressService.save(address);

        clearOtherDefaultIfNeeded(address, loginUser.getId());

        return R.ok();
    }

    /**
     * 当前登录会员修改收货地址
     *
     * <p>先按 id 查出来核对 memberId，确认这条地址确实是他的再改 ——
     * 不然随便传个别人的地址 id 就能改掉别人的收货地址。
     */
    @PostMapping("/update")
    public R updateAddress(@RequestBody MemberReceiveAddressEntity address, HttpSession session) {
        MemberResponseVo loginUser = loginUser(session);
        if (loginUser == null) {
            return notLogin();
        }

        if (address.getId() == null) {
            return R.error(BizCodeEnum.VALID_EXCEPTION.getCode(), "缺少地址 id");
        }

        MemberReceiveAddressEntity exist = memberReceiveAddressService.getById(address.getId());
        if (exist == null || !loginUser.getId().equals(exist.getMemberId())) {
            return R.error(BizCodeEnum.VALID_EXCEPTION.getCode(), "地址不存在或不属于当前会员");
        }

        // memberId 以库里那条为准，不让请求体覆盖
        address.setMemberId(exist.getMemberId());
        memberReceiveAddressService.updateById(address);

        clearOtherDefaultIfNeeded(address, exist.getMemberId());

        return R.ok();
    }

    /**
     * 个人中心删除自己的地址
     */
    @PostMapping("/deleteOwn")
    public R deleteOwn(@RequestParam("id") Long id, HttpSession session) {
        MemberResponseVo loginUser = loginUser(session);
        if (loginUser == null) {
            return notLogin();
        }
        MemberReceiveAddressEntity exist = memberReceiveAddressService.getById(id);
        if (exist == null || !loginUser.getId().equals(exist.getMemberId())) {
            return R.error(BizCodeEnum.VALID_EXCEPTION.getCode(), "地址不存在或不属于当前会员");
        }
        memberReceiveAddressService.removeById(id);
        return R.ok();
    }

    /**
     * 个人中心把某条地址设为默认
     */
    @PostMapping("/setDefault")
    public R setDefault(@RequestParam("id") Long id, HttpSession session) {
        MemberResponseVo loginUser = loginUser(session);
        if (loginUser == null) {
            return notLogin();
        }
        MemberReceiveAddressEntity exist = memberReceiveAddressService.getById(id);
        if (exist == null || !loginUser.getId().equals(exist.getMemberId())) {
            return R.error(BizCodeEnum.VALID_EXCEPTION.getCode(), "地址不存在或不属于当前会员");
        }

        // 1. 先清掉该会员其它默认地址，再设置目标地址
        memberReceiveAddressService.update(new UpdateWrapper<MemberReceiveAddressEntity>()
                .eq("member_id", loginUser.getId())
                .set("default_status", 0));
        memberReceiveAddressService.update(new UpdateWrapper<MemberReceiveAddressEntity>()
                .eq("id", id)
                .eq("member_id", loginUser.getId())
                .set("default_status", DEFAULT_STATUS_YES));
        return R.ok();
    }

    /**
     * 如果这次提交把它设成了默认地址，就把该会员其它地址的默认标记清掉
     *
     * <p>一个会员只能有一个默认地址，否则页面上"选中的那个"就说不清了
     */
    private void clearOtherDefaultIfNeeded(MemberReceiveAddressEntity address, Long memberId) {
        if (address.getDefaultStatus() == null || address.getDefaultStatus() != DEFAULT_STATUS_YES) {
            return;
        }

        memberReceiveAddressService.update(new UpdateWrapper<MemberReceiveAddressEntity>()
                .eq("member_id", memberId)
                .eq("default_status", DEFAULT_STATUS_YES)
                .ne(address.getId() != null, "id", address.getId())
                .set("default_status", 0));
    }

    private MemberResponseVo loginUser(HttpSession session) {
        return (MemberResponseVo) session.getAttribute(AuthServerConstant.LOGIN_USER);
    }

    private R notLogin() {
        return R.error(BizCodeEnum.MEMBER_NOT_LOGIN.getCode(), BizCodeEnum.MEMBER_NOT_LOGIN.getMsg());
    }

    /**
     * 列表
     */
    @RequestMapping("/list")
    @RequiresPermissions("member:memberreceiveaddress:list")
    public R list(@RequestParam Map<String, Object> params){
        PageUtils page = memberReceiveAddressService.queryPage(params);

        return R.ok().put("page", page);
    }


    /**
     * 信息
     */
    @RequestMapping("/info/{id}")
    @RequiresPermissions("member:memberreceiveaddress:info")
    public R info(@PathVariable("id") Long id){
		MemberReceiveAddressEntity memberReceiveAddress = memberReceiveAddressService.getById(id);

        return R.ok().put("memberReceiveAddress", memberReceiveAddress);
    }

    /**
     * 删除
     *
     * <p>注：后台管理那套 {@code @RequiresPermissions("/save")}、{@code "/update"} 接口已经删掉了 ——
     * 它们和会员端接口<b>路径完全一样</b>（都是 {@code /save}、{@code /update}），
     * 两个映射同时存在 Spring 启动就会因为 ambiguous mapping 失败。
     * 而且那套要 Shiro 登录态、本模块并没有配，本来就调不通，留着的只是死代码
     */
    @RequestMapping("/delete")
    @RequiresPermissions("member:memberreceiveaddress:delete")
    public R delete(@RequestBody Long[] ids){
		memberReceiveAddressService.removeByIds(Arrays.asList(ids));

        return R.ok();
    }

}
