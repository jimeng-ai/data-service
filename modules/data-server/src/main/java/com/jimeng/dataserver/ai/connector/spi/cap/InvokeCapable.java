package com.jimeng.dataserver.ai.connector.spi.cap;

import com.jimeng.dataserver.ai.connector.model.InvokeResult;

import java.util.Map;

/**
 * 能调用：执行一次操作。
 *
 * <p><b>必须显式声明幂等性。</b>框架据此决定能不能自动重试——这不是洁癖：
 * 自动重试一个非幂等的写操作，就是重复下单、重复退款。
 * 不声明的一律按「非幂等」处理，宁可不重试。
 */
public interface InvokeCapable {

    /**
     * @param operation 操作标识。HTTP 连接器是 {@code "GET /v1/orders"} 这种；其它类型自定义
     * @param params    参数
     */
    InvokeResult invoke(String operation, Map<String, Object> params);

    /**
     * 这个操作是否幂等。默认 {@code false} —— 不声明就不重试。
     */
    default boolean isIdempotent(String operation) {
        return false;
    }
}
