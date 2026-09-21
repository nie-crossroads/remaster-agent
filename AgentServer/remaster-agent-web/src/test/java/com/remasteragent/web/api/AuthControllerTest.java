package com.remasteragent.web.api;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/auth/me} 的返回约定。
 *
 * <p>这个端点的价值全在「角色怎么给」。两条约定必须锁住：
 * <ol>
 *   <li>发出去的角色名<b>不带</b> {@code ROLE_} 前缀 —— 那是 Spring Security 的接线细节，
 *       泄漏到前端就等于让界面去理解服务端框架的约定；</li>
 *   <li>不是角色的权限（如 {@code SCOPE_read}）不能混进角色列表 ——
 *       一旦混进去，前端 {@code roles.includes('ROOT')} 这类判据就从「角色判断」退化成「有没有权限」，
 *       语义会慢慢漂移。</li>
 * </ol>
 */
@DisplayName("AuthController：当前登录者身份")
class AuthControllerTest {

    private final AuthController controller = new AuthController();

    /** 造一个「已通过鉴权」的 Authentication —— 控制器方法体不做任何判断，所以这里只需塞权限。 */
    private static Authentication authenticated(String username, String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                username, "n/a", AuthorityUtils.createAuthorityList(authorities));
    }

    @Test
    @DisplayName("root：角色剥掉 ROLE_ 前缀后原样给出")
    void rootRolesAreUnprefixed() {
        AuthController.MeView me = controller.me(authenticated("root", "ROLE_DEMO", "ROLE_ROOT"));

        assertEquals("root", me.username());
        assertEquals(List.of("DEMO", "ROOT"), me.roles());
    }

    @Test
    @DisplayName("demo：只有 DEMO，不含 ROOT")
    void demoHasNoRootRole() {
        AuthController.MeView me = controller.me(authenticated("demo", "ROLE_DEMO"));

        assertEquals(List.of("DEMO"), me.roles());
        assertFalse(me.roles().contains(AuthController.ROOT_ROLE));
    }

    @Test
    @DisplayName("非 ROLE_ 开头的权限不进角色列表")
    void nonRoleAuthoritiesAreIgnored() {
        AuthController.MeView me = controller.me(authenticated("root", "ROLE_ROOT", "SCOPE_read"));

        assertEquals(List.of("ROOT"), me.roles());
    }

    @Test
    @DisplayName("一个角色都没有时返回空列表，而不是报错")
    void noAuthoritiesYieldsEmptyRoles() {
        AuthController.MeView me = controller.me(authenticated("someone"));

        assertTrue(me.roles().isEmpty());
    }
}
