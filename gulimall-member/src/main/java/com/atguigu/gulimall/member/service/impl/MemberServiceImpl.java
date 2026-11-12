package com.atguigu.gulimall.member.service.impl;

import com.atguigu.gulimall.api.auth_server.SocialUser;
import com.atguigu.gulimall.api.auth_server.UserLoginVo;
import com.atguigu.gulimall.api.auth_server.UserRegisterVo;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.member.dao.MemberLevelDao;
import com.atguigu.gulimall.member.entity.MemberLevelEntity;
import com.atguigu.gulimall.member.feign.ThirdPartyFeignClient;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.UUID;
import java.util.Map;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;

import com.atguigu.gulimall.member.dao.MemberDao;
import com.atguigu.gulimall.member.entity.MemberEntity;
import com.atguigu.gulimall.member.service.MemberService;


@Service("memberService")
public class MemberServiceImpl extends ServiceImpl<MemberDao, MemberEntity> implements MemberService {

    @Autowired
    private MemberLevelDao memberLevelDao;

    @Autowired
    private ThirdPartyFeignClient thirdPartyFeignClient;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<MemberEntity> page = this.page(
                new Query<MemberEntity>().getPage(params),
                new QueryWrapper<MemberEntity>()
        );

        return new PageUtils(page);
    }

    /**
     * 注册用户
     *
     * @param vo 用户注册信息
     * @throws RRException 如果用户名或手机号已存在，则抛出异常
     */
    @Override
    public void regist(UserRegisterVo vo) {
        MemberEntity memberEntity = new MemberEntity();

        // 1.检查用户名与手机号是否唯一
        boolean usernameExists = this.exists(new QueryWrapper<MemberEntity>().eq("username", vo.getUserName()));
        if (usernameExists) {
            throw new RRException(BizCodeEnum.USER_NAME_EXISTS, vo.getUserName());
        }
        boolean phoneExists = this.exists(new QueryWrapper<MemberEntity>().eq("mobile", vo.getPhone()));
        if (phoneExists) {
            throw new RRException(BizCodeEnum.PHONE_EXISTS, vo.getPhone());
        }
        memberEntity.setUsername(vo.getUserName());
        // 昵称默认取用户名 —— 不设的话首页"你好，xxx"后面是空的
        memberEntity.setNickname(vo.getUserName());
        memberEntity.setMobile(vo.getPhone());

        // 2.密码加密处理
        /*
          BCrypt —— 当前密码存储的主流选择，也是 Spring Security 的默认算法。

          注意 BCrypt 不是 MD5，两者毫无关系：
            - BCrypt 基于 Blowfish 派生（EksBlowfish），自带随机盐，故意设计得很慢（cost 因子 = 2^cost 轮）
            - MD5 是快速摘要，早已被破解，即使自己加盐也不该用于密码

          核心特性：同一密码每次 encode() 结果都不同（盐是随机的），所以只能靠 matches() 校验，存储后无法再获取原密码。
          存库只需一个 varchar(60) 字段，盐和 cost 都编在哈希串里（格式：$2a$12$...）。
         */
        PasswordEncoder encoder = new BCryptPasswordEncoder(12);
        memberEntity.setPassword(encoder.encode(vo.getPassword()));

        // 3.获取默认会员等级
        MemberLevelEntity memberLevelEntity = memberLevelDao.getDefaultLevelId();
        memberEntity.setLevelId(memberLevelEntity.getId());
        this.save(memberEntity);
    }

    /**
     * 账号密码登录
     *
     * @param vo 用户登录信息
     * @return 登录成功的会员
     * @throws RRException 用户名或密码错误时抛出
     */
    @Override
    public MemberEntity login(UserLoginVo vo) {
        String loginAcct = vo.getLoginacct();
        String password = vo.getPassword();

        // 账号既可能是用户名，也可能是手机号
        MemberEntity memberEntity = this.getOne(new QueryWrapper<MemberEntity>()
                .eq("username", loginAcct).or().eq("mobile", loginAcct));

        if (memberEntity == null) {
            // 注意这里和下面的密码错误抛的是【同一个】异常、同一条消息 ——
            // 不能让调用方从错误信息里分辨出"用户不存在"还是"密码错了"，
            // 否则等于送给攻击者一个批量枚举用户名的接口
            throw new RRException(BizCodeEnum.LOGIN_ACCT_PASSWORD_INVALID);
        }

        // 社交登录注册的账号没有密码（password 为空），只能用社交方式进 ——
        // 不走这一步的话，空密码会一路走到 BCrypt.matches(null)，
        // 虽然它返回 false 不会崩，但会刷一堆 "Empty encoded password" 警告
        if (!StringUtils.hasText(memberEntity.getPassword())) {
            throw new RRException(BizCodeEnum.LOGIN_ACCT_PASSWORD_INVALID);
        }

        PasswordEncoder encoder = new BCryptPasswordEncoder();
        if (!encoder.matches(password, memberEntity.getPassword())) {
            throw new RRException(BizCodeEnum.LOGIN_ACCT_PASSWORD_INVALID);
        }

        return memberEntity;
    }

    /**
     * 社交登录
     *
     * <p>完整流程：授权码 → 社交用户 → 查会员 → 没有就自动注册 → 返回会员
     *
     * <p>GitHub 和微信走的是<b>同一条链路</b>，只有「拿 code 换用户」那一步不同 ——
     * 那步由 {@code source} 决定调 third-party 的哪个接口，换回来都是统一的 SocialUser。
     *
     * @param code   社交平台回调带回来的授权码
     * @param source 渠道标识：github / wechat
     * @return 登录成功的会员
     */
    @Override
    public MemberEntity oauthLogin(String code, String source) {

        // 1、拿授权码去 third-party 换社交用户信息（GitHub 还是微信，由 source 分派）
        R r = thirdPartyFeignClient.oauthToken(source, code);
        if (r.getCode() != 0) {
            throw new RRException(BizCodeEnum.OAUTH_LOGIN_EXCEPTION);
        }
        SocialUser socialUser = r.getData("data", new TypeReference<SocialUser>() {
        });
        if (socialUser == null || !StringUtils.hasText(socialUser.getUid())) {
            // third-party 说成功但内容不对，按失败处理，别让脏数据进库
            throw new RRException(BizCodeEnum.OAUTH_LOGIN_EXCEPTION);
        }

        // 2、按 social_uid 查会员（uid 是平台侧稳定不变的 ID）
        MemberEntity memberEntity = this.getOne(
                new QueryWrapper<MemberEntity>().eq("social_uid", socialUser.getUid()));

        if (memberEntity != null) {
            // 老用户：刷新令牌后直接登录
            memberEntity.setAccessToken(socialUser.getAccess_token());
            memberEntity.setExpiresIn(String.valueOf(socialUser.getExpires_in()));
            this.updateById(memberEntity);
            return memberEntity;
        }

        // 3、新用户：自动注册
        return registerBySocialUser(socialUser);
    }

    /**
     * 首次社交登录时自动建会员
     *
     * <p>注意：这里<b>不设 password</b> —— 该账号只能用社交方式登录，
     * 密码留空即"无法用账号密码登录"。若给个默认密码，等于所有社交注册
     * 的用户共用同一把钥匙。
     */
    private MemberEntity registerBySocialUser(SocialUser socialUser) {

        MemberEntity memberEntity = new MemberEntity();
        memberEntity.setSocialUid(socialUser.getUid());
        memberEntity.setAccessToken(socialUser.getAccess_token());
        memberEntity.setExpiresIn(String.valueOf(socialUser.getExpires_in()));
        memberEntity.setNickname(socialUser.getNickname());
        memberEntity.setHeader(socialUser.getHeader());
        memberEntity.setEmail(socialUser.getEmail());

        // username 在库里唯一；GitHub 的 login 可能和已有用户撞名，撞了就加随机后缀
        memberEntity.setUsername(generateUniqueUsername(socialUser.getLogin()));

        // 默认会员等级
        MemberLevelEntity memberLevelEntity = memberLevelDao.getDefaultLevelId();
        memberEntity.setLevelId(memberLevelEntity.getId());

        memberEntity.setStatus(1);
        memberEntity.setCreateTime(new Date());

        this.save(memberEntity);
        return memberEntity;
    }

    /**
     * 生成一个库里不存在的 username
     *
     * <p>优先用社交平台的 login 原名；撞名了拼 6 位随机串重试；
     * 连续撞 5 次（几乎不可能）用时间戳兜底。
     */
    private String generateUniqueUsername(String login) {
        String base = StringUtils.hasText(login) ? login : "gh_user";
        // 库里 username 有长度上限，先截断留出后缀的位置
        if (base.length() > 24) {
            base = base.substring(0, 24);
        }

        if (!this.exists(new QueryWrapper<MemberEntity>().eq("username", base))) {
            return base;
        }

        for (int i = 0; i < 5; i++) {
            String candidate = base + "_" + UUID.randomUUID().toString().substring(0, 6);
            if (!this.exists(new QueryWrapper<MemberEntity>().eq("username", candidate))) {
                return candidate;
            }
        }

        return base + "_" + System.currentTimeMillis();
    }

}