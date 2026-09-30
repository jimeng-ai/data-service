package com.jimeng.dataserver.ai.connector.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RowEstimateNoteTest {

    @Test
    @DisplayName("追加：行数未知或为 0 不写；注释为空时只剩后缀；两样都没有是 null")
    void 追加() {
        assertEquals("订单表（约 5 行，InnoDB 估算值，不可当作准确计数）", RowEstimateNote.append("订单表", 5L));
        assertEquals("订单表", RowEstimateNote.append("订单表", null));
        assertEquals("订单表", RowEstimateNote.append("订单表", 0L));
        assertEquals("（约 7 行，InnoDB 估算值，不可当作准确计数）", RowEstimateNote.append("  ", 7L));
        assertNull(RowEstimateNote.append(null, null));
        assertNull(RowEstimateNote.append("", 0L));
    }

    @Test
    @DisplayName("★ 往返：strip(append(c, n)) == c —— 写入方和剥离方永远说同一句话")
    void 往返() {
        for (String c : List.of("订单表", "GB5D 公司代码", "带（括号）的注释", "客户-财务视图明细")) {
            for (Long n : Arrays.asList(null, 0L, 1L, 123456L)) {
                assertEquals(c, RowEstimateNote.strip(RowEstimateNote.append(c, n)), c + " / " + n);
            }
        }
        assertNull(RowEstimateNote.strip(RowEstimateNote.append(null, 7L)), "只有后缀 = 客户没写注释");
    }

    @Test
    @DisplayName("剥离：认旧措辞；只剥末尾；空白与 null 都是 null")
    void 剥离() {
        assertEquals("订单表", RowEstimateNote.strip("订单表（约 8371 行，InnoDB 估算值）"));
        assertEquals("（约 5 行，InnoDB 估算值，不可当作准确计数）备注",
                RowEstimateNote.strip("（约 5 行，InnoDB 估算值，不可当作准确计数）备注"));
        assertNull(RowEstimateNote.strip("   "));
        assertNull(RowEstimateNote.strip(null));
    }
}
