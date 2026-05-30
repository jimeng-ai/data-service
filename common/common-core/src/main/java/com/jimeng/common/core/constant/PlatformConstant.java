package com.jimeng.common.core.constant;

import java.util.Arrays;
import java.util.List;

/**
 * 平台级常量：登录域、保留租户、权限模块码。
 */
public final class PlatformConstant {

    private PlatformConstant() {
    }

    /** 平台运营的保留租户 ID（运营 JWT 的 tenant_id 写这个，满足 TenantContextFilter）。 */
    public static final String PLATFORM_TENANT = "platform";

    /** 历史/默认数据归属租户。 */
    public static final String DEFAULT_TENANT = "default";

    /** 登录域。写入 JWT 的 realm claim，前端据此分门户。 */
    public static final String REALM_OPERATOR = "OPERATOR";
    public static final String REALM_ENTERPRISE = "ENTERPRISE";

    // ---------------------------------------------------------------- 权限模块码（MENU 授权）
    public static final String MODULE_AGENT = "AGENT_MODULE";
    public static final String MODULE_KB = "KB_MODULE";
    public static final String MODULE_CHAT = "CHAT_MODULE";
    public static final String MODULE_PLUGIN = "PLUGIN_MODULE";

    /** 全部可授权模块码。 */
    public static final List<String> ALL_MODULES =
            Arrays.asList(MODULE_AGENT, MODULE_KB, MODULE_CHAT, MODULE_PLUGIN);
}
