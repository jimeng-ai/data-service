package com.jimeng.dataserver.ai.http.policy;

import java.net.InetAddress;

/**
 * 网关第 4 步：DNS 解析出的一条记录能不能连（设计文档 §4.3、§4.4）。可注入是为了测试：单测要连 127.0.0.1 上的假上游，
 * 生产代码一律用 {@link #standard}。
 */
@FunctionalInterface
public interface AddressPolicy {

    /**
     * @param declaredHost 目标域名是不是 key 或 skill 声明过的（而不是靠 web:true 放进来的）
     * @return null = 放行；否则是拒绝原因，会原样给到模型和审计日志
     */
    String check(String host, InetAddress ip, boolean declaredHost);

    static AddressPolicy standard(PrivateAllowList allowPrivate) {
        return (host, ip, declaredHost) -> {
            if (!BlockedAddresses.isBlocked(ip)) return null;
            if (!BlockedAddresses.isHardBlocked(ip) && declaredHost && allowPrivate.permits(host, ip)) return null;
            return host + " 指向内网地址 " + ip.getHostAddress() + "，不允许访问";
        };
    }
}
