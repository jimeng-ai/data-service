package com.jimeng.dataserver.ai.http;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用 HTTP 网关的配置（前缀 {@code http-gateway}，设计文档 §4）。每一项都有字段默认值：缺了 Nacos 键不该让服务起不来。
 * 单次请求的总超时不在这里：由放行目标域名的 skill 自己的 {@code metadata.jm.timeout-seconds} 决定（默认 60，最多 180）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "http-gateway")
public class HttpGatewayProperties {

    /**
     * 沙箱回调网关的根地址，含 /data，例如 {@code http://localhost:20011/data}（边车所在宿主看到的地址）。
     * 空 = 沙箱平面不下发 http_request / $JM_HTTP（对话平面不受影响）。与 connector.agent.callback-base-url 一样，刻意没有默认地址。
     */
    private String callbackBaseUrl = "";

    /** 私有化部署显式放行的内网 CIDR 或域名（§4.4）。默认空，SaaS 不配。写错启动即失败。 */
    private List<String> allowPrivate = new ArrayList<>();

    private int connectTimeoutSeconds = 10;
    private int maxRedirects = 3;
    /** 回包上限（§4.3 第 7 步）：工具 2 MB（给模型的文本另行截到 toolTextMaxChars），脚本 50 MB。 */
    private int toolMaxBytes = 2 * 1024 * 1024;
    private int scriptMaxBytes = 50 * 1024 * 1024;
    private int toolTextMaxChars = 20_000;
    /** 请求体上限：工具 1 MB（模型写的参数），脚本 20 MB（上传文件给客户系统）。 */
    private int toolRequestMaxBytes = 1024 * 1024;
    private int scriptRequestMaxBytes = 20 * 1024 * 1024;
}
