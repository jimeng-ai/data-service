package com.jimeng.dataserver.ai.connector.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写策略枚举。
 *
 * <p><b>这里真正要锁住的只有一件事：{@code parse()} 的回落方向。</b>
 * 往严的方向回落（落到 FORBIDDEN）出错时表现为「该能写的写不了」——吵闹、当场被发现；
 * 往宽的方向回落出错时表现为「本该只读的连接悄悄能改客户的生产数据」——没有任何信号，
 * 直到某次模型写了一条 UPDATE 才知道。两种错的代价完全不对称，所以回落方向必须有回归保护。
 *
 * <p>这类静默降级本仓踩过不止一次。枚举加值时最容易顺手写成「认不出来就当成上一档」，
 * 下面那组参数化用例就是拦这个的。
 */
class WritePolicyTest {

    @Nested
    @DisplayName("parse 一律往 FORBIDDEN 回落")
    class ParseFallsBackToForbidden {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {
                "   ",          // 存量行被人手工清空
                "\t\n",
                "null",         // 前端把 null 序列化成了字符串
                "undefined",
                "0",
                "1",
                "true",
                "false",
                "READ_WRITE",   // 别处的命名习惯串过来
                "WRITE",
                "ALLOW",
                "RW",
                "FORBID",       // 少几个字母，看着像对的
                "AUTO_LIMITED", // 将来可能新增、但这个版本还不认识的值
                "REQUIRE-APPROVAL",
                "REQUIRE APPROVAL",
                "审批"
        })
        void 认不出来的值一律判为只读(String raw) {
            assertEquals(WritePolicy.FORBIDDEN, WritePolicy.parse(raw),
                    "回落方向错了：不确定的值必须当成只读，绝不能当成能写");
            assertFalse(WritePolicy.parse(raw).allowsWrite());
        }

        /**
         * ★ 这条是给「以后往枚举里加值的人」准备的。
         *
         * <p>新加一档如果忘了处理解析，parse 会把它落到 FORBIDDEN——那是安全的、也是预期的。
         * 但若有人为了省事把 catch 分支改成「回落到上一档」，这条立刻红。
         */
        @Test
        void 未来新增的未知值也落到只读() {
            assertEquals(WritePolicy.FORBIDDEN, WritePolicy.parse("SOME_FUTURE_POLICY_V2"));
        }
    }

    @Nested
    @DisplayName("parse 能认出的写法")
    class ParseAccepts {

        @ParameterizedTest
        @EnumSource(WritePolicy.class)
        void 每个枚举名都能原样解析回自己(WritePolicy p) {
            // 这条同时覆盖将来新增的值：漏了它就意味着那一档配置上去也生效不了，
            // 而界面显示的还是它——一个不报错的配置失效。
            assertEquals(p, WritePolicy.parse(p.name()));
        }

        @ParameterizedTest
        @EnumSource(WritePolicy.class)
        void 小写与首尾空白也能解析(WritePolicy p) {
            // 库里的值是人手工改过的、也可能来自不同版本的前端，大小写与空白都不可控。
            assertEquals(p, WritePolicy.parse(" " + p.name().toLowerCase() + " "));
        }

        @Test
        void 大小写混写能解析() {
            assertEquals(WritePolicy.AUTO, WritePolicy.parse("aUtO"));
            assertEquals(WritePolicy.REQUIRE_APPROVAL, WritePolicy.parse("Require_Approval"));
            assertEquals(WritePolicy.FORBIDDEN, WritePolicy.parse("Forbidden"));
        }
    }

    @Nested
    @DisplayName("allowsWrite")
    class AllowsWrite {

        @Test
        void 只有只读那一档不允许写() {
            assertFalse(WritePolicy.FORBIDDEN.allowsWrite());
            assertTrue(WritePolicy.REQUIRE_APPROVAL.allowsWrite());
            assertTrue(WritePolicy.AUTO.allowsWrite());
        }

        @Test
        void 待审批也算允许写() {
            // 容易写反的一处：REQUIRE_APPROVAL 在平台侧是「放行到审批队列」，不是「拦死」。
            // 把它归到不允许写，写通道会在生成审批单之前就报错，审批流整条链路失效。
            assertTrue(WritePolicy.REQUIRE_APPROVAL.allowsWrite());
        }
    }

    @Nested
    @DisplayName("label")
    class Label {

        @Test
        void 三档的短名() {
            assertEquals("只读", WritePolicy.FORBIDDEN.label());
            assertEquals("写需审批", WritePolicy.REQUIRE_APPROVAL.label());
            assertEquals("写自动", WritePolicy.AUTO.label());
        }

        @ParameterizedTest
        @EnumSource(WritePolicy.class)
        void 每一档都有非空短名(WritePolicy p) {
            // label() 是 switch 穷举的，新增枚举值不补分支会编译失败——这条只兜住「补了个空串」。
            // 界面上这一格是空的，超管就看不出这条连接到底能不能写。
            assertNotNull(p.label());
            assertFalse(p.label().isBlank());
        }
    }
}
