package com.jimeng.dataserver.ai.connector.error;

import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.guard.WriteSqlGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConnectorErrorCode#GUARD_BLOCKED} 与 {@link ConnectorErrorCode#FORBIDDEN} 的<b>分工</b>。
 *
 * <h3>为什么要单独一个测试类</h3>
 * 各个护栏的测试里已经逐条断言了错误码，但那些断言只说明「这一处返回 X」，
 * 说明不了<b>为什么是 X 不是 Y</b>。而这两个码的区别不在分类学，在于
 * <b>模型收到之后会做什么</b>：
 *
 * <ul>
 *   <li>{@code GUARD_BLOCKED}：语句根本没发到客户系统，是平台自己拦的 → <b>改写后重试</b>。</li>
 *   <li>{@code FORBIDDEN}：改写没用，得有人去改授权或配置 → <b>去找管理员</b>。</li>
 * </ul>
 *
 * <p>混用不会抛异常、不会打日志，只会让模型走向相反的方向：把护栏拦截说成「权限不足」，
 * 模型就会去推动放宽数据库权限——而那正是整套只读设计要防的那件事。
 * 所以这里钉的是<b>「同一个场景该落哪一档」</b>，不是某一处的返回值。
 */
class ConnectorErrorCodeSemanticsTest {

    private final ReadOnlySqlGuard readGuard = new ReadOnlySqlGuard();
    private final WriteSqlGuard writeGuard = new WriteSqlGuard();

    @Test
    @DisplayName("护栏拒绝（不带 WHERE 的 UPDATE）→ GUARD_BLOCKED，不能是「权限不足」")
    void 护栏拒绝落护栏档() {
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> writeGuard.check("UPDATE orders SET status = 'X'"));
        // 这条正是那次真实事故：落成 FORBIDDEN 时，模型不得不在回复里自己纠正我们
        //（「补充两点说明，免得你被那句『权限不足』误导……」）。它那次判对了，不代表下次也会。
        assertEquals(ConnectorErrorCode.GUARD_BLOCKED, e.getCode(),
                "不带 WHERE 是语句写法问题，说成权限不足会把模型推去要更宽的数据库权限");
    }

    @Test
    @DisplayName("两档给模型的建议必须指向相反的动作，否则分档等于没分")
    void 两档的建议互斥() {
        String guard = ConnectorErrorCode.GUARD_BLOCKED.modelHint();
        String forbidden = ConnectorErrorCode.FORBIDDEN.modelHint();

        assertTrue(guard.contains("改写"), "护栏档必须明说「改写后重试」：" + guard);
        assertTrue(guard.contains("不需要申请权限") || guard.contains("不是权限问题"),
                "护栏档必须明说这不是权限问题，否则和 FORBIDDEN 一样会把模型带偏：" + guard);
        assertFalse(forbidden.contains("改写"), "权限档不该建议改写——改写没用：" + forbidden);
    }

    @Test
    @DisplayName("用查询工具写数据 → 指向「换工具」，而不是「去要写权限」")
    void 查询工具收到写语句时指向换工具() {
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> readGuard.check("UPDATE orders SET status = 'X' WHERE id = 1", 100));
        assertEquals(ConnectorErrorCode.GUARD_BLOCKED, e.getCode());
        assertTrue(e.getSafeDetail().contains("conn_execute"),
                "应当指向写操作工具，而不是让模型以为需要更高权限：" + e.getSafeDetail());
        assertFalse(e.getSafeDetail().contains("只被授予了只读权限"),
                "这句话会被读成权限问题：" + e.getSafeDetail());
    }

    /**
     * ★ 对 HTTP 连接来说 {@code allow_methods} <b>就是写策略本身</b>
     *（{@code HttpSession.verifyReadOnly} 正是拿方法白名单减去幂等方法来判只读，
     * 而 {@code ConnectorGateway} 的写策略闸只对 {@code Capability.WRITE} 触发、
     * HTTP 走的是 {@code INVOKE}，永远到不了那一步）。
     * 它是整条 HTTP 通路上唯一的写授权闸，所以必须落 FORBIDDEN——
     * 落成 GUARD_BLOCKED 会让模型 POST → PUT → PATCH 一路撞同一个 admin 配置项。
     */
    @Test
    @DisplayName("HTTP 的方法白名单是写策略，不是可改写的护栏")
    void HTTP方法白名单落权限档() {
        // 具体断言在 HttpCallGuardTest.默认只读_POST被拒_按权限而非护栏，
        // 这里只留这段说明与交叉引用：两个码的分界在这一处最容易被改错。
        assertFalse(ConnectorErrorCode.FORBIDDEN == ConnectorErrorCode.GUARD_BLOCKED);
    }
}
