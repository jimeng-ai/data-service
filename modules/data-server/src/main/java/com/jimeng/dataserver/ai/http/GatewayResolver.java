package com.jimeng.dataserver.ai.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** 网关第 4 步的 DNS 解析。可注入是为了测试「锁定 IP、不再解析」；生产用 {@link #SYSTEM}。 */
@FunctionalInterface
public interface GatewayResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;

    GatewayResolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));
}
