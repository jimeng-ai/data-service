package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 星图从「数据连接」里每个库的卡片进，和数据连接一样只给企业超管（设计文档 §9）。 */
class DataGraphControllerTest {

    @Test
    @DisplayName("非企业超管：两个接口都被拒，而且一行数据都不查")
    void 非超管被拒() {
        SuperAdminGuard guard = mock(SuperAdminGuard.class);
        DataGraphService service = mock(DataGraphService.class);
        when(guard.requireSuperAdmin())
                .thenThrow(new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "需要企业超级管理员权限"));
        DataGraphController controller = new DataGraphController(guard, service);

        assertThrows(ServiceException.class, () -> controller.system("7"));
        assertThrows(ServiceException.class, () -> controller.table("7", "t_order"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("企业超管：每个接口先过超管校验，再原样交给服务")
    void 超管放行() {
        SuperAdminGuard guard = mock(SuperAdminGuard.class);
        DataGraphService service = mock(DataGraphService.class);
        DataGraphViews.SystemGraph graph = new DataGraphViews.SystemGraph(
                "7", "erp", null, "READY", false, null, List.of(), List.of());
        when(service.system("7")).thenReturn(graph);
        DataGraphController controller = new DataGraphController(guard, service);

        assertSame(graph, controller.system("7"));
        controller.table("7", "t_order");
        verify(guard, times(2)).requireSuperAdmin();
        verify(service).table("7", "t_order");
    }
}
