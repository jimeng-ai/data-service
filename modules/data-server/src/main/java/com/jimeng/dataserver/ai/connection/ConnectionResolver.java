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
            conn.setAllowPaths(parsePaths(c.getAllowPaths(), c.getName()));
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
     * JSON 数组 → 列表。解析失败时返回 null 让边车回落到默认 ["/**"]——
     * 注意这是<b>放宽</b>方向的回落，所以必须 warn；真要收紧应当在录入时校验。
     */
    private List<String> parsePaths(String json, String name) {
        if (json == null || json.isBlank()) return null;
        try {
            return CommonUtil.getObjectMapper().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("连接 {} 的 allow_paths 不是合法 JSON 数组，本次按默认 [/**] 处理：{}", name, e.getMessage());
            return null;
        }
    }
}
