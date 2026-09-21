package com.jimeng.dataserver.ai.connection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把「这个 Agent 被授予了哪些连接」解析成派发给边车的 {@link SidecarRunPayload.Conn} 列表。
 *
 * <p>这是凭据从库里出来、进入派发载荷的唯一位置。明文只在这一步短暂存在于内存，
 * 随载荷发给边车，再由边车注册进 egress 代理。容器全程见不到。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionResolver {

    private final ConnectionMapper connectionMapper;
    private final AgentConnectionMapper agentConnectionMapper;
    private final CredentialCipher cipher;

    /**
     * 解析某个 Agent 本次 run 可用的连接。
     *
     * @param agentId null（无 agent 上下文）→ 返回空列表。与技能不同，连接<b>没有</b>
     *                「无信息则全放行」这一态：连接是新增能力，没有需要兼容的历史隐式集，
     *                因此一律默认不授予。授权上的默认值只能是"否"。
     */
    public List<SidecarRunPayload.Conn> resolveForAgent(Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        Set<Long> connIds = agentConnectionMapper
                .selectList(new LambdaQueryWrapper<AgentConnection>().eq(AgentConnection::getAgentId, agentId))
                .stream().map(AgentConnection::getConnectionId).collect(Collectors.toSet());
        if (connIds.isEmpty()) {
            return List.of();
        }
        List<Connection> rows = connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .in(Connection::getId, connIds)
                .eq(Connection::getStatus, "ACTIVE"));

        List<SidecarRunPayload.Conn> out = new ArrayList<>(rows.size());
        for (Connection c : rows) {
            // ★ 只有 HTTP 连接器才走 egress 代理这条路。这一条不是优化，是凭据边界。
            //
            // 本方法把 credential_cipher 解密后塞进 Conn.token 发给边车，而 egress 代理的语义是
            // 「把 token 当 HTTP 凭据按源 IP 注入到 baseUrl 那个上游」。对 MYSQL 连接器，
            // credential_cipher 装的是【数据库密码】、base_url 是 null（ConnectorService 只给 HTTP
            // 回写旧列），于是不过滤 kind 的后果有两个，都不该发生：
            //   (a) 一条明文的生产库密码被序列化进派发载荷、发到 :8088 —— 那个进程没有任何理由持有它；
            //   (b) 边车 egress.ts 的 c.baseUrl.replace(...) 在 undefined 上抛 TypeError，
            //       registerRun 在容器启动前就失败 ⇒ 整轮 run 报错，而报错信息跟「绑了个 MySQL 连接」
            //       毫无字面关联，没人会往凭据上想。
            //
            // 数据库类连接器有自己的通道：conn_* 工具经宿主回调执行，凭据全程不出 data-service
            // （见 ai/connector/agent/callback）。两条路不能混用。
            if (!"HTTP".equalsIgnoreCase(c.getKind())) {
                log.debug("连接 {} 的 kind={} 不走 egress 代理（数据库类连接器经 conn_* 宿主回调执行），本次跳过",
                        c.getName(), c.getKind());
                continue;
            }
            if (!"direct".equalsIgnoreCase(c.getTransport())) {
                // tunnel（经客户侧连接器进内网）尚未实现。跳过而不是报错：一条连接没配好
                // 不该让整轮对话失败；但必须留下日志，否则又是一次"能力静默消失"。
                log.warn("连接 {} 的 transport={} 尚未支持，本次跳过", c.getName(), c.getTransport());
                continue;
            }
            String token;
            try {
                token = cipher.decrypt(c.getCredentialCipher(), c.getEncryptionVersion() == null ? 0 : c.getEncryptionVersion());
            } catch (Exception e) {
                log.warn("连接 {} 的凭据不可用，本次跳过：{}", c.getName(), e.getMessage());
                continue;
            }
            SidecarRunPayload.Conn conn = new SidecarRunPayload.Conn();
            conn.setName(c.getName());
            conn.setBaseUrl(c.getBaseUrl());
            conn.setToken(token);
            conn.setScheme(c.getAuthScheme() == null ? "bearer" : c.getAuthScheme());
            conn.setAllowMethods(parseMethods(c.getAllowMethods()));
            try {
                conn.setAllowPaths(parsePaths(c.getAllowPaths(), c.getName()));
            } catch (RuntimeException e) {
                // 宁可这条连接本轮不可用，也不能把它按「全路径放行」发出去。
                log.warn("连接 {} 的路径白名单不可用，本次跳过（不按默认全放行）：{}", c.getName(), e.getMessage());
                continue;
            }
            out.add(conn);
        }
        return out;
    }

    /** 逗号分隔 → 大写列表。空则交给边车用它的默认（只读）。 */
    private List<String> parseMethods(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase).toList();
    }

    /**
     * JSON 数组 → 列表；留空表示调用方未限制路径，交给边车的默认值。
     *
     * <p><b>解析失败抛出而不是回落。</b>原先解析不了就返回 null，而 null 在边车那边等于
     * 默认放行 {@code ["/**"]} —— 一个坏掉的 allow_paths 字段把这条连接从「只开放几个路径」
     * 静默放宽成「整个上游全开」。往放宽方向的静默回落是这套系统反复踩的那一类坑，
     * 所以改成让调用方跳过这条连接：少一条连接是看得见的，多开的权限是看不见的。
     */
    private List<String> parsePaths(String json, String name) {
        if (json == null || json.isBlank()) return null;
        try {
            return CommonUtil.getObjectMapper().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(
                    "连接 " + name + " 的 allow_paths 不是合法 JSON 数组：" + e.getMessage(), e);
        }
    }
}
