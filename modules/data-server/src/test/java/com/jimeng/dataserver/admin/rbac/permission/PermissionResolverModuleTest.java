package com.jimeng.dataserver.admin.rbac.permission;

import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import com.jimeng.persistence.mapper.SysRoleResourceMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import com.jimeng.persistence.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class PermissionResolverModuleTest {

    private static final String DENIED = "没有数据星图的访问权限";

    private PermissionResolver resolverWith(ResolvedPermissions permissions) {
        PermissionResolver resolver = spy(new PermissionResolver(mock(SysUserMapper.class),
                mock(SysUserRoleMapper.class), mock(SysRoleResourceMapper.class), mock(AgentMapper.class),
                mock(KnowledgeBaseMapper.class), mock(AiSkillMapper.class)));
        doReturn(permissions).when(resolver).resolveCurrent();
        return resolver;
    }

    @Test
    @DisplayName("角色被授予了这个模块的成员：放行")
    void 有模块的成员放行() {
        PermissionResolver resolver = resolverWith(new ResolvedPermissions(false, "MEMBER",
                Set.of(PlatformConstant.MODULE_DATA_GRAPH), null, null));
        assertDoesNotThrow(() -> resolver.assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED));
    }

    /** 业务码 4001 按平台约定走 HTTP 200：前端只在 401/403 时踢回登录页，这里不会把成员踢出去。 */
    @Test
    @DisplayName("没有这个模块的成员：4001，文案原样带出")
    void 没有模块的成员被拒() {
        PermissionResolver resolver = resolverWith(new ResolvedPermissions(false, "MEMBER",
                Set.of(PlatformConstant.MODULE_AGENT), null, null));

        ServiceException e = assertThrows(ServiceException.class,
                () -> resolver.assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED));

        assertEquals(ExceptionCode.AUTHENTICATION_FAIL.getResultCode(), e.getRespCode());
        assertEquals(DENIED, e.getRespMsg());
    }

    @Test
    @DisplayName("企业超管：不看授权，恒通过")
    void 超管恒通过() {
        PermissionResolver resolver = resolverWith(new ResolvedPermissions(true, "SUPER_ADMIN",
                Set.of(), null, null));
        assertDoesNotThrow(() -> resolver.assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED));
    }
}
