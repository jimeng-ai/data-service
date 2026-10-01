package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataGraphControllerTest {

    private static final String DENIED = "没有数据星图的访问权限";

    @Test
    @DisplayName("没有「数据星图」模块：三个接口都被拒，而且一行数据都不查")
    void 没有模块被拒() {
        PermissionResolver resolver = mock(PermissionResolver.class);
        DataGraphService service = mock(DataGraphService.class);
        doThrow(new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, DENIED))
                .when(resolver).assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED);
        DataGraphController controller = new DataGraphController(resolver, service);

        assertEquals(DENIED, assertThrows(ServiceException.class, controller::systems).getRespMsg());
        assertThrows(ServiceException.class, () -> controller.system("7"));
        assertThrows(ServiceException.class, () -> controller.table("7", "t_order"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("有模块（或企业超管）：每个接口先查模块，再原样交给服务")
    void 有模块放行() {
        PermissionResolver resolver = mock(PermissionResolver.class);
        DataGraphService service = mock(DataGraphService.class);
        List<DataGraphViews.SystemSummary> systems = List.of();
        when(service.systems()).thenReturn(systems);
        DataGraphController controller = new DataGraphController(resolver, service);

        assertSame(systems, controller.systems());
        controller.system("7");
        controller.table("7", "t_order");
        verify(resolver, times(3)).assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED);
        verify(service).system("7");
        verify(service).table("7", "t_order");
    }
}
