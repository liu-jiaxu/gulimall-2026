package com.atguigu.gulimall.auth_server.web;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.api.auth_server.UserLoginVo;
import com.atguigu.gulimall.auth_server.agent.AgentJwtService;
import com.atguigu.gulimall.auth_server.feign.MemberFeignClient;
import com.atguigu.gulimall.auth_server.service.LoginRedirectService;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.utils.R;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * @ClassName: LoginController
 * @Package: com.atguigu.gulimall.auth_server.web
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/1 - 15:45
 * @Version: v1.0
 */
@Controller
public class LoginController {

    /** 登录成功后重定向要用 */
    @Value("${gulimall.shop-home-url}")
    private String shopHomeUrl;
    @Value("${gulimall.auth-url}")
    private String authUrl;
    @Value("${gulimall.order-url}")
    private String orderUrl;
    @Value("${gulimall.cart-url}")
    private String cartUrl;
    @Value("${gulimall.seckill-url}")
    private String seckillUrl;
    @Value("${gulimall.member-url}")
    private String memberUrl;

    @Autowired
    private MemberFeignClient memberFeignClient;

    @Autowired
    private LoginRedirectService loginRedirectService;

    @Autowired
    private AgentJwtService agentJwtService;

    @GetMapping(path = {"", "/login.html"})
    public String login(@RequestParam(value = "returnUrl", required = false) String returnUrl,
                        Model model) {
        // 从秒杀/购物车跳过来时带上原地址，登录后可以回去
        model.addAttribute("returnUrl", returnUrl);
        return "login";
    }

    /**
     * 用户密码登录，成功后写入 Spring Session 和用户端 agent JWT Cookie。
     *
     * @param userLoginVo 登录表单
     * @param returnUrl 登录后要返回的站内地址，可为空
     * @param redirectAttributes 登录失败时的提示参数
     * @param session 当前用户 Session
     * @param response 当前 HTTP 响应，用于写 agent JWT Cookie
     * @return 登录成功后的重定向地址
     */
    @PostMapping("/login")
    public String login(UserLoginVo userLoginVo,
                        @RequestParam(value = "returnUrl", required = false) String returnUrl,
                        RedirectAttributes redirectAttributes,
                        HttpSession session,
                        HttpServletResponse response) {

        R login = memberFeignClient.login(userLoginVo);
        if (login.getCode() == 0) {
            // 登录成功：把会员信息写进 session（Spring Session → Redis）。
            // 前台 gulimall.com 才读得到 —— 和社交登录走的是同一条路，
            // 只是前面拿凭证的方式不同
            MemberResponseVo member = login.getData("data", new TypeReference<MemberResponseVo>() {
            });
            session.setAttribute(AuthServerConstant.LOGIN_USER, member);
            // 登录成功同时签发 agent 专用 JWT，写入 HttpOnly Cookie
            agentJwtService.issue(member, response);
            // 只允许跳回商城自己的域名，防止开放重定向
            String target = isSafeReturnUrl(returnUrl) ? returnUrl : shopHomeUrl;
            // 没有默认地址先去个人中心设置，设置完再回目标页
            return loginRedirectService.afterLogin(member, target);
        } else {
            // 登录失败
            redirectAttributes.addFlashAttribute("msg", login.get("msg").toString());
            return "redirect:" + authUrl + "/login.html";
        }

    }

    private boolean isSafeReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return false;
        }
        return returnUrl.startsWith(shopHomeUrl)
                || returnUrl.startsWith(authUrl)
                || returnUrl.startsWith(orderUrl)
                || returnUrl.startsWith(cartUrl)
                || returnUrl.startsWith(seckillUrl)
                || returnUrl.startsWith(memberUrl);
    }

    /**
     * 退出登录
     *
     * <p>{@code invalidate()} 会把整个会话作废 —— 对 Spring Session 而言，
     * 就是删掉 Redis 里那条会话记录，同时下发一个过期的 cookie。
     * 之后前台再读到这个 cookie 也查不到会话，等同于未登录。
     *
     * @param session 当前用户 Session
     * @param response 当前 HTTP 响应，用于清除 agent JWT Cookie
     * @return 退出后的登录页地址
     */
    @GetMapping("/logout.html")
    public String logout(HttpSession session, HttpServletResponse response) {
        session.invalidate();
        agentJwtService.clear(response);
        return "redirect:" + authUrl + "/login.html";
    }

}
