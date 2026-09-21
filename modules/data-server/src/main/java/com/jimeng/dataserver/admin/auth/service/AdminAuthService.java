package com.jimeng.dataserver.admin.auth.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWTPayload;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.security.JwtSecretProvider;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.auth.dto.ChangePasswordRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginResponse;
import com.jimeng.persistence.entity.SysEnterprise;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 企业账号（{@code sys_user}：超管 + 成员）登录、改密、查询当前用户。
 * jm-agent-front 与 jm-admin 企业门户共用 {@code /data/admin/auth/**}。
 *
 * <p>JWT 直接用 hutool 签发；密钥来自配置（{@link JwtSecretProvider}），gateway 的 {@code AuthorizeFilter}
 * 用同一密钥校验，并据 {@code tenant_id} claim 注入 {@code X-Tenant-Id}。固定 12 小时。
 *
 * <p>{@code sys_user}/{@code sys_enterprise} 均不在租户白名单内，查询不会被自动注入 {@code tenant_id}；
 * 登录发生在 TenantContext 设置之前，按 username 全局解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    /** Token 过期时间（毫秒），12h 友好。 */
    public static final long ADMIN_TOKEN_EXPIRE_MS = 12L * 60 * 60 * 1000;

    /** 语义层 agent 回调 token 的 {@code purpose} claim。回调前缀上的过滤器只认带这个值的 token，带它的 token 也只能访问回调前缀。 */
    public static final String PURPOSE_SEMANTIC_AGENT = "semantic-agent";

    /**
     * 语义层 agent 回调 token 在本片墙钟之外多给的秒数，与 RAG 回调 token 同一口径（{@code AgentExecService} 按
     * wallClockSec + 120 秒签发）。一片从签发到结束，除了墙钟本身还有边车准入排队（最长 30 秒）和容器启动，
     * token 不能赶在运行结束之前先过期，否则最后几次提交会在网关上 401、白跑一片。
     * 多给的这段不扩大重放窗口：回调前缀的过滤器每次查库，批次一结束、换片或被 cancel（{@code current_run_id} 置空）
     * token 就提前失效。
     */
    public static final int SEMANTIC_AGENT_TOKEN_GRACE_SEC = 120;

    /**
     * 对话 agent 连接器回调 token 的 {@code purpose} claim。
     *
     * <p><b>必须是与 {@link #PURPOSE_SEMANTIC_AGENT} 不同的值</b>：两条回调前缀上各有一个过滤器，
     * 各自只认自己的 purpose、也只放行自己的前缀。复用同一个值，一枚语义层 token 就成了
     * 「那个 Agent 全部连接器的读写凭据」，而两边的资源绑定检查谁都不会发现。
     */
    public static final String PURPOSE_CONNECTOR_AGENT = "connector-agent";

    /**
     * 对话 agent 连接器回调 token 在本次运行墙钟之外多给的秒数，与语义层、RAG 回调同一口径。
     * 一次运行从签发到结束，除墙钟本身还有边车准入排队和容器启动，token 不能赶在运行结束之前先过期，
     * 否则最后几次 {@code conn_*} 调用会被拒、而模型只会当成"查不到"继续往下说。
     *
     * <p>多给的这段<b>不扩大重放窗口</b>：回调前缀的过滤器每次查
     * {@code ConnectorAgentRunRegistry}，运行一结束登记即删，token 提前失效。
     */
    public static final int CONNECTOR_AGENT_TOKEN_GRACE_SEC = 120;

    private final SysUserMapper sysUserMapper;
    private final SysEnterpriseMapper sysEnterpriseMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtSecretProvider jwtSecretProvider;

    @Transactional
    public LoginResponse login(LoginRequest req) {
        if (req == null || StrUtil.isBlank(req.getUsername()) || StrUtil.isBlank(req.getPassword())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "用户名或密码不能为空");
        }
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, req.getUsername()));
        if (user == null) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号已禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "用户名或密码错误");
        }
        requireEnterpriseEnabled(user.getTenantId());

        user.setLastLoginAt(new Date());
        sysUserMapper.updateById(user);

        return buildResponse(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        if (req == null || StrUtil.isBlank(req.getOldPassword()) || StrUtil.isBlank(req.getNewPassword())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "旧密码或新密码不能为空");
        }
        if (req.getNewPassword().length() < 6) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "新密码至少 6 位");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "账号不存在");
        }
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "旧密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        sysUserMapper.updateById(user);
    }

    public LoginResponse.AdminUserView getCurrentUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "账号不存在");
        }
        return toView(user);
    }

    /**
     * 滑动续期：用当前仍有效的 token 换发一枚新的 12h token。重新校验账号 + 企业状态，避免给已禁用账号续命。
     */
    public LoginResponse refresh(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号不存在");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号已禁用");
        }
        requireEnterpriseEnabled(user.getTenantId());
        return buildResponse(user);
    }

    private void requireEnterpriseEnabled(String tenantId) {
        SysEnterprise ent = TenantContext.runAsSystem(() -> sysEnterpriseMapper.selectOne(
                Wrappers.<SysEnterprise>lambdaQuery().eq(SysEnterprise::getTenantId, tenantId)));
        if (ent == null || ent.getStatus() == null || ent.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "企业已停用");
        }
    }

    private LoginResponse buildResponse(SysUser user) {
        return LoginResponse.builder()
                .token(signToken(user))
                .expiresIn(ADMIN_TOKEN_EXPIRE_MS / 1000)
                .user(toView(user))
                .build();
    }

    private LoginResponse.AdminUserView toView(SysUser user) {
        return LoginResponse.AdminUserView.builder()
                .id(user.getId())
                .tenantId(user.getTenantId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .realm(PlatformConstant.REALM_ENTERPRISE)
                .userType(user.getUserType())
                .build();
    }

    private String signToken(SysUser user) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ADMIN_TOKEN_EXPIRE_MS);
        Map<String, Object> payload = new HashMap<>();
        payload.put(JWTPayload.ISSUED_AT, now);
        payload.put(JWTPayload.EXPIRES_AT, exp);
        payload.put(JWTPayload.NOT_BEFORE, now);
        // gateway AuthorizeFilter 读 id / tenant_id claim 并分别注入 user-id / X-Tenant-Id 头
        payload.put("id", String.valueOf(user.getId()));
        payload.put("tenant_id", user.getTenantId());
        payload.put("username", user.getUsername());
        payload.put("realm", PlatformConstant.REALM_ENTERPRISE);
        payload.put("user_type", user.getUserType());
        return cn.hutool.jwt.JWTUtil.createToken(payload, jwtSecretProvider.key());
    }

    /**
     * 为内部服务（代码执行 Agent 沙箱边车）签发一枚短时效 JWT，使其能"以该用户身份"回调
     * 走网关的接口（如 /data/rag/search）。复用同一密钥，gateway 据 tenant_id claim 注入 X-Tenant-Id。
     * ttl 应只覆盖一次运行（分钟级），缩小被盗用窗口。
     */
    public String mintInternalToken(String userId, String tenantId, long ttlMs) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlMs);
        Map<String, Object> payload = new HashMap<>();
        payload.put(JWTPayload.ISSUED_AT, now);
        payload.put(JWTPayload.EXPIRES_AT, exp);
        payload.put(JWTPayload.NOT_BEFORE, now);
        payload.put("id", userId);
        payload.put("tenant_id", tenantId);
        payload.put("realm", PlatformConstant.REALM_ENTERPRISE);
        return cn.hutool.jwt.JWTUtil.createToken(payload, jwtSecretProvider.key());
    }

    /**
     * 语义层 agent 一片运行的回调 token 有效期（毫秒）= (本片墙钟秒数 + {@link #SEMANTIC_AGENT_TOKEN_GRACE_SEC}) × 1000。
     * 口径集中在这里，派发方不各自重算。
     */
    public static long semanticAgentTokenTtlMs(int sliceWallClockSec) {
        return (sliceWallClockSec + (long) SEMANTIC_AGENT_TOKEN_GRACE_SEC) * 1000L;
    }

    /**
     * 为语义层 agent 的<b>一片、一次尝试</b>签发窄权限回调 token。与 {@link #mintInternalToken} 同密钥、同 hutool 写法，
     * 网关照常验签并据 {@code tenant_id} 注入租户；范围限定由 data-server 回调前缀上的过滤器执行。
     *
     * <h3>claims</h3>
     * <ul>
     *   <li>{@code id}：触发人（建连或点「重新生成」的超管，批次行 {@code triggered_by}），字符串，与登录签发、
     *       {@link #mintInternalToken} 一致。<b>网关要求非空</b>，缺了直接 401。</li>
     *   <li>{@code tenant_id}：批次的租户，网关据此注入 {@code X-Tenant-Id}。</li>
     *   <li>{@code realm}：ENTERPRISE，同上两处。</li>
     *   <li>{@code purpose}：{@link #PURPOSE_SEMANTIC_AGENT}。</li>
     *   <li>{@code gen} / {@code cid}：批次 id、连接 id，<b>字符串</b>——雪花 id 超出 JS 安全整数，
     *       按数字写进 JWT 会在任何 JS 端解析时被静默改值。</li>
     *   <li>{@code slice} / {@code rid}：片号与本次尝试的 runId。只校验片号挡不住「同一片的上一次尝试」：
     *       编排器每次派发前写 {@code current_run_id}、运行一结束就置空，被杀掉的旧尝试若还有在途回调，
     *       rid 对不上就进不来。</li>
     * </ul>
     *
     * <p>token 只进 payload 的 {@code semanticContext.accessToken}，由边车宿主进程里的 MCP 服务闭包持有；
     * 不进容器 env、不进 prompt，也不回显在工具结果与错误文案里。调用方不要把它打进日志。
     *
     * @param ttlMs 用 {@link #semanticAgentTokenTtlMs(int)} 算
     */
    public String mintSemanticAgentToken(Long triggeredBy, String tenantId, Long generationId, Long connectorId,
                                         int sliceNo, String runId, long ttlMs) {
        Date now = new Date();
        Map<String, Object> payload = new HashMap<>();
        payload.put(JWTPayload.ISSUED_AT, now);
        payload.put(JWTPayload.NOT_BEFORE, now);
        payload.put(JWTPayload.EXPIRES_AT, new Date(now.getTime() + ttlMs));
        payload.put("id", String.valueOf(triggeredBy));          // 网关要求非空（AuthorizeFilter 缺 id 即 401）
        payload.put("tenant_id", tenantId);                     // 网关据此注入 X-Tenant-Id
        payload.put("realm", PlatformConstant.REALM_ENTERPRISE);
        payload.put("purpose", PURPOSE_SEMANTIC_AGENT);
        payload.put("gen", String.valueOf(generationId));       // 字符串：雪花 id 超出 JS 安全整数
        payload.put("cid", String.valueOf(connectorId));
        payload.put("slice", sliceNo);
        payload.put("rid", runId);
        return cn.hutool.jwt.JWTUtil.createToken(payload, jwtSecretProvider.key());
    }

    /**
     * 对话 agent 一次沙箱运行的连接器回调 token 有效期（毫秒）
     * = (本次运行墙钟秒数 + {@link #CONNECTOR_AGENT_TOKEN_GRACE_SEC}) × 1000。
     * 口径<b>只留这一处</b>，派发方与 {@code ConnectorAgentRunRegistry.begin} 的 TTL 都从这里取：
     * 两处各自重算，早晚会算出「登记先于 token 过期」，表现是运行末尾的回调莫名其妙被拒。
     */
    public static long connectorAgentTokenTtlMs(int wallClockSec) {
        return (wallClockSec + (long) CONNECTOR_AGENT_TOKEN_GRACE_SEC) * 1000L;
    }

    /**
     * 为对话 agent 的<b>一次沙箱运行</b>签发窄权限连接器回调 token。与 {@link #mintSemanticAgentToken}
     * 同密钥、同 hutool 写法、HS256；网关照常验签并据 {@code tenant_id} 注入租户，
     * 范围限定由 {@code ConnectorAgentScopeFilter} 执行。
     *
     * <h3>claims</h3>
     * <ul>
     *   <li>{@code id}：发起这轮对话的用户，字符串，与登录签发一致。<b>网关要求非空</b>，缺了直接 401。</li>
     *   <li>{@code tenant_id}：本轮租户，网关据此注入 {@code X-Tenant-Id}。</li>
     *   <li>{@code realm}：ENTERPRISE。</li>
     *   <li>{@code purpose}：{@link #PURPOSE_CONNECTOR_AGENT}。</li>
     *   <li>{@code aid}：Agent id，<b>字符串</b>——雪花 id 超出 JS 安全整数，
     *       按数字写进 JWT 会在任何 JS 端解析时被静默改值（沙箱是 Node 宿主进程）。</li>
     *   <li>{@code rid}：本次运行 id。它是吊销的抓手：运行结束即从
     *       {@code ConnectorAgentRunRegistry} 删除，在途回调随即进不来。</li>
     * </ul>
     *
     * <p><b>刻意不把"被授权的连接 id 集合"写进 claims</b>：那是一份快照，超管撤销授权要等到下一轮才生效。
     * 连接归属由 {@code ConnectorGateway} 按 {@code agent_connection} 实时查。
     *
     * <p>token 只进下发给边车的 payload、由宿主进程里的工具代理闭包持有；不进容器 env、不进 prompt，
     * 也不回显在工具结果与错误文案里。调用方不要把它打进日志。
     *
     * @param ttlMs 用 {@link #connectorAgentTokenTtlMs(int)} 算
     */
    public String mintConnectorAgentToken(Long userId, String tenantId, Long agentId, String runId, long ttlMs) {
        Date now = new Date();
        Map<String, Object> payload = new HashMap<>();
        payload.put(JWTPayload.ISSUED_AT, now);
        payload.put(JWTPayload.NOT_BEFORE, now);
        payload.put(JWTPayload.EXPIRES_AT, new Date(now.getTime() + ttlMs));
        payload.put("id", String.valueOf(userId));              // 网关要求非空（AuthorizeFilter 缺 id 即 401）
        payload.put("tenant_id", tenantId);                     // 网关据此注入 X-Tenant-Id
        payload.put("realm", PlatformConstant.REALM_ENTERPRISE);
        payload.put("purpose", PURPOSE_CONNECTOR_AGENT);
        payload.put("aid", String.valueOf(agentId));            // 字符串：雪花 id 超出 JS 安全整数
        payload.put("rid", runId);
        return cn.hutool.jwt.JWTUtil.createToken(payload, jwtSecretProvider.key());
    }
}
