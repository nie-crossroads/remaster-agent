package com.remasteragent.web.api;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录者身份。
 *
 * <h2>为什么需要一个「我是谁」端点</h2>
 * <p>Basic 鉴权是<b>无状态</b>的：登录这个动作在客户端只是把凭据记下来，服务端不留任何痕迹，
 * 所以「我刚填的账号密码对不对」「我是 demo 还是 root」这两件事，客户端凭自己是答不上来的 ——
 * 以前的做法是把凭据先存下来、等真正调用业务端点时再撞 401，于是输错密码的人要先点一次
 * 「运行示例」才知道自己没登录成功。
 *
 * <p>这个端点把身份确认提前到登录那一刻（{@code AuthController} 挂在 {@code authenticated()} 后面，
 * 凭据不对直接 401），同时把<b>角色</b>交给前端 —— 前端不再需要、也不应该自己推断谁是 root。
 *
 * <h2>为什么剥掉 ROLE_ 前缀</h2>
 * <p>{@code ROLE_} 是 Spring Security 的<b>接线细节</b>：它要求
 * {@code hasRole("ROOT")} 对应权限名 {@code ROLE_ROOT}。把这个前缀原样发到前端，
 * 等于让界面去理解服务端框架的约定 —— 前端要判的就只是「有没有 ROOT 这个角色」。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * 全权限角色名（不含 {@code ROLE_} 前缀）。
     *
     * <p>与 {@code SecurityConfig} 里 {@code roles("DEMO", "ROOT")} 的写法对应，
     * 也和前端 {@code auth store} 的判据共用同一个字面量 —— 三处说的是同一件事。
     */
    public static final String ROOT_ROLE = "ROOT";

    /** Spring Security 给角色权限加的前缀，只在这里剥一次。 */
    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * 返回当前凭据对应的身份与角色。
     *
     * <p>{@code Authentication} 由 Spring Security 从请求的 {@code Authorization} 头解析并注入，
     * 到这里时已经校验通过 —— 方法体内不需要再做任何判断。
     */
    @GetMapping("/me")
    public MeView me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .sorted()
                .toList();
        return new MeView(authentication.getName(), roles);
    }

    /**
     * 身份视图。
     *
     * <p>刻意<b>不</b>带一个算好的 {@code isRoot} 布尔：角色列表已经是事实，
     * 再加一个派生字段就有两个来源，迟早出现「roles 里有 ROOT 但 isRoot 是 false」这种自相矛盾。
     * 判断留给唯一的使用方（前端），判据也就只有一处。
     *
     * @param username 登录名
     * @param roles    角色名，已剥掉 {@code ROLE_} 前缀（如 {@code ["DEMO", "ROOT"]}）
     */
    public record MeView(String username, List<String> roles) {
    }
}
