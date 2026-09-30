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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataGraphControllerTest {

    @Test
    @DisplayName("非企业超管：三个接口都被拒，而且一行数据都不查")
    void 非超管被拒() {
        SuperAdminGuard guard = mock(SuperAdminGuard.class);
        DataGraphService service = mock(DataGraphService.class);
        when(guard.requireSuperAdmin())
                .thenThrow(new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "需要企业超级管理员权限"));
        DataGraphController controller = new DataGraphController(guard, service);

        assertThrows(ServiceException.class, controller::systems);
        assertThrows(ServiceException.class, () -> controller.system("7"));
        assertThrows(ServiceException.class, () -> controller.table("7", "t_order"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("企业超管：原样交给服务")
    void 超管放行() {
        SuperAdminGuard guard = mock(SuperAdminGuard.class);
        DataGraphService service = mock(DataGraphService.class);
        List<DataGraphViews.SystemSummary> systems = List.of();
        when(service.systems()).thenReturn(systems);
        DataGraphController controller = new DataGraphController(guard, service);

        assertSame(systems, controller.systems());
        controller.system("7");
        controller.table("7", "t_order");
        verify(service).system("7");
        verify(service).table("7", "t_order");
    }
}
