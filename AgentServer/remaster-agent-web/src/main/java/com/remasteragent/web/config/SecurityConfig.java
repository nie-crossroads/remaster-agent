package com.remasteragent.web.config;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 最小化演示鉴权。
 *
 * <p>仅保护 {@code /api/demo/**} 与 {@code /api/auth/**}：这两组端点需要登录，
 * 其余端点保持原样，避免破坏双进程与评测 harness。采用 HTTP Basic（无状态）：
 * 前端把凭据放进 {@code Authorization} 头随每个请求发出，不依赖服务端 session。
 *
 * <h2>两个账号，一档差别</h2>
 * <p>内存里注册两个用户（凭据来自 {@link DemoProperties}，生产务必用环境变量覆盖）：
 * <ul>
 *   <li>{@code demo} → 角色 {@code DEMO}：访客用，能跑样本；工作台只看得到演示任务。</li>
 *   <li>{@code root} → 角色 {@code DEMO} + {@code ROOT}：机器主人用，任务列表显示全部历史任务。</li>
 * </ul>
 * 角色是<b>唯一事实来源</b>：后端在这里签发，前端通过 {@code GET /api/auth/me} 取回，
 * 自己不复述一遍「谁是 root」—— 否则界面以为自己是 root、而安全链不认，就成了两套说法。
 * 不建用户表、不做多租户 —— 与「演示模式刻意与多租户解耦」的立论一致。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final String[] allowedOrigins;

    public SecurityConfig(
            @Value("${remaster.web.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
            String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                // 预检请求必须放行：否则带 Authorization 头的跨域请求会被浏览器拦在 OPTIONS 阶段
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                // 状态查询免登录：落地页据此决定是否显示「运行示例工程」CTA
                .requestMatchers("/api/demo/status").permitAll()
                .requestMatchers("/api/demo/**").authenticated()
                // 当前登录者身份（角色）。登录页用它校验凭据，工作台用它决定列表可见范围
                .requestMatchers("/api/auth/**").authenticated()
                // 删除任务仅管理员（ROOT）可操作。建/查/取消/重跑等其余任务接口保持 permitAll
                // （评测 harness 与演示都依赖它们），删除是唯一的管控面。
                .requestMatchers(HttpMethod.DELETE, "/api/tasks/**").hasRole("ROOT")
                .anyRequest().permitAll())
            // 无状态 Basic：每个请求自带凭据，服务端不存 session
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> basic
                .realmName("RemasterAgent")
                // 用 HttpStatusEntryPoint 而不是默认的 BasicAuthenticationEntryPoint：
                // 后者会带上 `WWW-Authenticate: Basic`，而浏览器见到它会对 401 的 XHR
                // 弹一个**原生账号密码框** —— 我们自己的登录页正开着，突然冒出一个系统框，
                // 只会让人以为页面坏了（输错密码那条路径必然会撞上它）。
                // 这是一个 JSON API，本来也不该用 WWW-Authenticate 宣告认证方式。
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(Arrays.asList(allowedOrigins));
        cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS", "DELETE"));
        // Authorization 头由前端 Basic 鉴权拦截器手动带上，必须放行
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", cfg);
        return src;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder 认识 {noop} 前缀，配合下方存储的明文演示密码
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 演示网关的共享账号表。
     *
     * <p>同名冲突按**最小权限**处理：用户名重复时 {@code InMemoryUserDetailsManager}
     * 会因为重复键直接抛错（应用起不来），而「把两个角色并到一个账号上」又等于因为一个配置笔误
     * 把演示账号悄悄提权成 root。所以这里选择只保留 {@code demo} 并打 WARN ——
     * 少一个 root 是可见的（登录不进去、日志里有话），悄悄提权是不可见的。
     */
    @Bean
    public InMemoryUserDetailsManager demoUserDetailsManager(DemoProperties demoProperties) {
        UserDetails demo = User.withUsername(demoProperties.username())
                .password("{noop}" + demoProperties.password())
                .roles("DEMO")
                .build();

        if (demo.getUsername().equals(demoProperties.rootUsername())) {
            log.warn("remaster.demo.root-username 与 remaster.demo.username 相同（{}）："
                    + "同名账号会互相覆盖，已按最小权限只注册 demo 账号，root 不可登录。"
                    + "请把 root 用户名改成别的值。", demo.getUsername());
            return new InMemoryUserDetailsManager(demo);
        }

        UserDetails root = User.withUsername(demoProperties.rootUsername())
                .password("{noop}" + demoProperties.rootPassword())
                // root 同时持有 DEMO：它应当能做演示账号能做的一切，只是多一层「全量可见」
                .roles("DEMO", "ROOT")
                .build();

        // 只打用户名、不打密码 —— 日志经常被贴到 issue / 聊天里
        log.info("演示鉴权账号已注册：{}（角色 DEMO）与 {}（角色 DEMO+ROOT）",
                demo.getUsername(), root.getUsername());
        return new InMemoryUserDetailsManager(demo, root);
    }
}
