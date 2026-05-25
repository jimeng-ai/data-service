package com.jimeng.common.core.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void setAndGet() {
        assertFalse(TenantContext.isSet());
        assertNull(TenantContext.get());

        TenantContext.set("tenant-a");
        assertTrue(TenantContext.isSet());
        assertEquals("tenant-a", TenantContext.get());
        assertEquals("tenant-a", TenantContext.required());
    }

    @Test
    void requiredWithoutSet_throws() {
        assertThrows(IllegalStateException.class, TenantContext::required);
    }

    @Test
    void clear_removes() {
        TenantContext.set("tenant-a");
        TenantContext.clear();
        assertFalse(TenantContext.isSet());
        assertNull(TenantContext.get());
    }

    @Test
    void runAsSystem_inSystemMode() {
        TenantContext.set("tenant-a");
        assertFalse(TenantContext.isSystemMode());
        Boolean inside = TenantContext.runAsSystem(() -> {
            return TenantContext.isSystemMode();
        });
        assertTrue(inside);
        // 恢复后 system mode 应为 false
        assertFalse(TenantContext.isSystemMode());
        // tenant_id 不变
        assertEquals("tenant-a", TenantContext.get());
    }

    @Test
    void runAsSystem_nested_restoresPreviousState() {
        TenantContext.runAsSystem(() -> {
            assertTrue(TenantContext.isSystemMode());
            TenantContext.runAsSystem(() -> {
                assertTrue(TenantContext.isSystemMode());
            });
            assertTrue(TenantContext.isSystemMode());
        });
        assertFalse(TenantContext.isSystemMode());
    }
}
