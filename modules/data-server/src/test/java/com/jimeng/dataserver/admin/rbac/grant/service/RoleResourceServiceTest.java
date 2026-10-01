package com.jimeng.dataserver.admin.rbac.grant.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.dataserver.admin.rbac.grant.dto.GrantView;
import com.jimeng.dataserver.admin.rbac.role.service.RoleService;
import com.jimeng.persistence.entity.SysRoleResource;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import com.jimeng.persistence.mapper.SysRoleResourceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleResourceServiceTest {

    /**
     * jm-admin 的授权弹窗拿这里的模块码当勾选初值，保存时原样提交；保存按 {@code ALL_MODULES} 校验。
     * 下线的模块码要是回显出去，弹窗里没有它的勾选框、用户也去不掉，这个角色每次保存都会被「未知模块码」拒掉。
     */
    @Test
    @DisplayName("★ 已经下线的模块码（插件、数据星图）不回显，角色照常能保存")
    void 下线的模块码不回显() {
        // LambdaQueryWrapper 的列名解析要用 MP 的实体缓存；纯单测里没有 Spring，得自己灌一遍。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysRoleResource.class);
        SysRoleResourceMapper mapper = mock(SysRoleResourceMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                menu(PlatformConstant.MODULE_AGENT), menu("DATA_GRAPH_MODULE"),
                menu("PLUGIN_MODULE"), menu(PlatformConstant.MODULE_CHAT)));
        RoleResourceService service = new RoleResourceService(mapper, mock(RoleService.class),
                mock(AgentMapper.class), mock(KnowledgeBaseMapper.class));

        GrantView view = service.getGrants("t1", 5L);

        assertEquals(List.of(PlatformConstant.MODULE_AGENT, PlatformConstant.MODULE_CHAT), view.getModules());
    }

    private static SysRoleResource menu(String code) {
        SysRoleResource row = new SysRoleResource();
        row.setResourceType("MENU");
        row.setResourceId(0L);
        row.setResourceCode(code);
        return row;
    }
}
