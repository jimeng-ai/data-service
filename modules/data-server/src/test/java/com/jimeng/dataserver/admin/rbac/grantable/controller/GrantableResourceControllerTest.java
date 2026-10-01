package com.jimeng.dataserver.admin.rbac.grantable.controller;

import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.admin.rbac.grantable.dto.ModuleOption;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class GrantableResourceControllerTest {

    /**
     * jm-admin 的角色授权弹窗按这个接口渲染模块勾选框；保存时 {@code RoleResourceService} 按 {@code ALL_MODULES} 校验。
     * 两边少一个，这个模块就授不出去；多一个，勾上之后保存会被拒。
     */
    @Test
    @DisplayName("可授权模块与 ALL_MODULES 一一对应，数据星图在列")
    void 模块列表() {
        GrantableResourceController controller = new GrantableResourceController(
                mock(AgentMapper.class), mock(KnowledgeBaseMapper.class), mock(SuperAdminGuard.class));

        List<ModuleOption> modules = controller.modules();

        assertEquals(PlatformConstant.ALL_MODULES, modules.stream().map(ModuleOption::getCode).toList());
        assertEquals("数据星图", modules.stream()
                .filter(m -> PlatformConstant.MODULE_DATA_GRAPH.equals(m.getCode()))
                .findFirst().orElseThrow().getName());
    }
}
