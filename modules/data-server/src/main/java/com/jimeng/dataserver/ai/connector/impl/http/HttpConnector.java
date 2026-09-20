package com.jimeng.dataserver.ai.connector.impl.http;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.HttpCallGuard;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.ParamField;
import com.jimeng.dataserver.ai.connector.spi.ParamSpec;
import com.jimeng.dataserver.ai.connector.spi.ParamType;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * HTTP 接口连接器（<b>平台侧执行</b>）。
 *
 * <h3>它和现有的沙箱 egress 那条路是什么关系</h3>
 * 同一张 {@code connection} 表、同一条记录，<b>两条执行路径并存</b>：
 * <ul>
 *   <li><b>沙箱路</b>（既有，不动）：凭据下发给边车 → egress 代理按源 IP 注入 →
 *       容器内脚本 {@code curl $JM_CONN_BASE/<name>/...}</li>
 *   <li><b>平台路</b>（本类）：对话平面直接由 data-server 发请求，凭据从不离开 JVM</li>
 * </ul>
 * 技术架构 §2 要求「业务不进沙箱」，但既有那条路还在用、推倒重来会打断已有技能，
 * 所以是<b>并存而不是替换</b>。
 *
 * <h3>★ 由此产生的一个必须补上的缺口</h3>
 * {@code allow_methods} / {@code allow_paths} 这层白名单，今天<b>只有 egress 代理在执行</b>，
 * data-server 进程内零校验（读码确认：{@code ConnectionResolver} 只是把它们解析后塞进
 * 下发载荷）。执行收回平台侧后，这层保护会<b>凭空消失</b>——{@link HttpCallGuard} 就是为此存在。
 *
 * <h3>只读的承重点与数据库不同，而且更弱</h3>
 * MySQL 连接器的只读靠<b>客户侧的账号授权</b>承重（我们改不了它）；HTTP 连接器的只读靠
 * <b>我们自己的方法白名单</b>承重。后者是我们自己的代码，写错就没了。
 * 所以给一条 HTTP 连接开放写方法，风险实质上高于给数据库连接——产品侧要知道这个差别。
 */
@Component
public class HttpConnector implements Connector {

    public static final String KIND = "HTTP";

    private final HttpCallGuard callGuard;
    private final ConnectorProperties properties;
    private final OkHttpClient httpClient;

    /**
     * 复用 {@code pluginHttpClient} 而不是未限定名的那个。
     *
     * <p>未限定名的 {@code OkHttpClient} 是 {@code @Primary} 的 <b>LLM 大超时池</b>
     * （读超时按 {@code okhttp.read-timeout}，那是给流式模型调用用的量级）。
     * 用它发客户接口请求，一个挂住的上游能占着线程直到 LLM 级别的超时——而工具执行这一段
     * 完全不受 {@code AiConversationLoop} 的 5 分钟 latch 约束，等于没有上限。
     *
     * <p>每次调用再用 {@code newBuilder()} 覆盖超时（共享连接池与 dispatcher，不新建线程池）。
     */
    public HttpConnector(HttpCallGuard callGuard,
                         ConnectorProperties properties,
                         @Qualifier("pluginHttpClient") OkHttpClient httpClient) {
        this.callGuard = callGuard;
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String displayName() {
        return "HTTP 接口";
    }

    @Override
    public ParamSpec paramSpec() {
        return ParamSpec.of(
                ParamField.of("baseUrl", "接口基地址", ParamType.STRING, true,
                        "只支持 http/https，且不能带 user:pass。所有调用的路径都相对于它"),
                ParamField.of("authScheme", "认证方式", ParamType.ENUM, false,
                                "bearer → Authorization: Bearer <凭据>；api-key → X-API-Key: <凭据>。"
                                        + "签名式鉴权（按请求内容现算 HMAC）尚未支持")
                        .withOptions(List.of("bearer", "api-key")).withDefault("bearer"),
                ParamField.secret("credential", "凭据", true,
                        "令牌明文。加密存储。已保存的令牌可以在编辑里查看，每次查看都会记一条使用记录；"
                                + "不主动更换就不会改动它"),
                ParamField.of("allowMethods", "允许的方法", ParamType.STRING_LIST, false,
                        "★ 默认只有 GET（只读）。写方法必须在这里显式声明——"
                                + "这条连接的「只读」完全由这个白名单承重，不像数据库还有客户侧账号权限兜底"),
                ParamField.of("allowPaths", "允许的路径", ParamType.STRING_LIST, false,
                        "路径 glob，`**` 跨段、`*` 段内，例如 /v1/orders/**。留空等价于 [\"/**\"]（全放行）"),
                ParamField.of("timeoutSeconds", "调用超时（秒）", ParamType.INT, false, "默认 20")
                        .withDefault("20").withRange(1, 120)
        );
    }

    @Override
    public Set<Capability> declaredCapabilities() {
        // 不含 DESCRIBE：接口自描述要等 OpenAPI 导入那一期（P4）。现在声明了也探测不出东西，
        // 只会让界面上多一个「支持但用不了」的能力——又一次静默降级。
        return Set.of(Capability.INVOKE, Capability.HEALTH);
    }

    @Override
    public ConnectorSession open(ConnectorInstance instance) {
        String baseUrl = instance.str("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "这条连接没有配置接口基地址");
        }
        String credential = instance.credential();
        if (credential == null || credential.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "这条连接没有保存凭据，请在管理台重新填写");
        }
        int timeoutSec = instance.intVal("timeoutSeconds", properties.getInvoke().getTimeoutSeconds());
        return new HttpSession(instance, httpClient, callGuard, baseUrl, credential,
                instance.str("authScheme", "bearer"), timeoutSec,
                properties.getInvoke().getMaxResponseBytes());
    }
}
