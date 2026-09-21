package com.jimeng.dataserver.admin.common;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link UserNameResolver#displayNameOf}：口径依据里「由张三确认」的那个「张三」从哪来。
 *
 * <p>重点不在「能取到名字」，而在那道<b>租户校验</b>：{@code sys_user} 不在租户白名单里，
 * 查它走的是 {@code runAsSystem}（跨租户）。少了这道校验，一个从别处流进来的 userId
 * 换回来的会是<b>别的企业的员工姓名</b>。
 */
class UserNameResolverTest {

    private SysUserMapper mapper;
    private UserNameResolver resolver;

    @BeforeEach
    void setUp() {
        mapper = mock(SysUserMapper.class);
        resolver = new UserNameResolver(mapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static SysUser user(String tenantId, String display, String username) {
        SysUser u = new SysUser();
        u.setId(7L);
        u.setTenantId(tenantId);
        u.setDisplayName(display);
        u.setUsername(username);
        return u;
    }

    @Test
    @DisplayName("同租户：拿到显示名")
    void 同租户取名() {
        TenantContext.set("t1");
        when(mapper.selectById(eq(7L))).thenReturn(user("t1", "张三", "zhangsan"));

        assertEquals("张三", resolver.displayNameOf(7L));
    }

    @Test
    @DisplayName("显示名为空时回退用户名")
    void 回退用户名() {
        TenantContext.set("t1");
        when(mapper.selectById(eq(7L))).thenReturn(user("t1", "  ", "zhangsan"));

        assertEquals("zhangsan", resolver.displayNameOf(7L));
    }

    @Test
    @DisplayName("★ 跨租户一律返回 null——绝不能把别家企业的员工姓名漏出去")
    void 跨租户拒绝() {
        TenantContext.set("t1");
        when(mapper.selectById(eq(7L))).thenReturn(user("t2", "李四", "lisi"));

        assertNull(resolver.displayNameOf(7L));
    }

    @Test
    @DisplayName("系统态（无 TenantContext）不做租户校验：运营侧本来就跨租户")
    void 系统态放行() {
        when(mapper.selectById(eq(7L))).thenReturn(user("t2", "李四", "lisi"));

        assertNull(TenantContext.get());
        assertEquals("李四", resolver.displayNameOf(7L));
    }

    @Test
    @DisplayName("用户不存在 / id 为 null：返回 null，不抛——记不下人也绝不能让记口径失败")
    void 取不到返回null() {
        TenantContext.set("t1");
        when(mapper.selectById(eq(9L))).thenReturn(null);

        assertNull(resolver.displayNameOf(9L));
        assertNull(resolver.displayNameOf(null));
    }
}
