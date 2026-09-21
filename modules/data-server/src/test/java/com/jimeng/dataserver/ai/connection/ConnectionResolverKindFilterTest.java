package com.jimeng.dataserver.ai.connection;

import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ConnectionResolver#resolveForAgent}：哪些连接可以被下发给边车的 egress 代理。
 *
 * <h3>这里钉的是一条凭据边界，不是一条过滤规则</h3>
 * 本方法把 {@code credential_cipher} 解密后当作 {@code Conn.token} 发给边车，而 egress 代理的语义是
 * 「把 token 当 HTTP 凭据注入到 baseUrl 那个上游」。对 <b>MYSQL</b> 连接器，那个 cipher 装的是
 * <b>数据库密码</b>、{@code base_url} 是 null。
 *
 * <p>没有 kind 过滤时，把一条 MySQL 连接授予 Agent 的实际后果是：
 * <ol>
 *   <li>一条明文生产库密码被序列化进派发载荷、发到 {@code :8088} —— 那个进程没有任何理由持有它；</li>
 *   <li>边车 {@code egress.ts} 在 {@code undefined.replace(...)} 上抛 TypeError，容器启动前 run 就失败。</li>
 * </ol>
 * 第 2 条让第 1 条几乎不可能被发现：用户看到的是「绑了连接之后 Agent 坏了」，没人会往凭据上想。
 *
 * <p>数据库类连接器有自己的通道（conn_* 宿主回调，凭据不出 data-service）。两条路不能混用。
 */
class ConnectionResolverKindFilterTest {

    private ConnectionMapper connectionMapper;
    private AgentConnectionMapper agentConnectionMapper;
    private CredentialCipher cipher;
    private ConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        agentConnectionMapper = mock(AgentConnectionMapper.class);
        cipher = mock(CredentialCipher.class);
        resolver = new ConnectionResolver(connectionMapper, agentConnectionMapper, cipher);

        AgentConnection grant = new AgentConnection();
        grant.setAgentId(1L);
        grant.setConnectionId(10L);
        when(agentConnectionMapper.selectList(any())).thenReturn(List.of(grant));
        when(cipher.decrypt(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("s3cr3t-db-password");
    }

    private static Connection row(String kind, String baseUrl) {
        Connection c = new Connection();
        c.setId(10L);
        c.setName("erp");
        c.setKind(kind);
        c.setTransport("direct");
        c.setStatus("ACTIVE");
        c.setBaseUrl(baseUrl);
        c.setCredentialCipher("cipher-blob");
        c.setEncryptionVersion(1);
        return c;
    }

    @Test
    @DisplayName("★ MYSQL 连接绝不进 egress connections——那条 token 是数据库密码，baseUrl 还是 null")
    void mysql不下发() {
        when(connectionMapper.selectList(any())).thenReturn(List.of(row("MYSQL", null)));

        List<SidecarRunPayload.Conn> out = resolver.resolveForAgent(1L);

        assertTrue(out.isEmpty(), "MySQL 连接不该出现在发给边车的 connections 里");
    }

    @Test
    @DisplayName("HTTP 连接照常下发")
    void http照常下发() {
        when(connectionMapper.selectList(any())).thenReturn(List.of(row("HTTP", "https://api.example.com")));

        List<SidecarRunPayload.Conn> out = resolver.resolveForAgent(1L);

        assertEquals(1, out.size());
        assertEquals("erp", out.get(0).getName());
        assertEquals("https://api.example.com", out.get(0).getBaseUrl());
        assertEquals("s3cr3t-db-password", out.get(0).getToken());
    }

    @Test
    @DisplayName("kind 为 null 的历史行按非 HTTP 处理——授权上的默认值只能是「否」")
    void kind为空按不下发() {
        when(connectionMapper.selectList(any())).thenReturn(List.of(row(null, "https://api.example.com")));

        assertTrue(resolver.resolveForAgent(1L).isEmpty());
    }

    @Test
    @DisplayName("★ allow_paths 坏掉时跳过该连接，而不是按默认 [/**] 全放行")
    void 路径白名单坏掉不放宽() {
        Connection c = row("HTTP", "https://api.example.com");
        c.setAllowPaths("{不是合法的 JSON 数组");
        when(connectionMapper.selectList(any())).thenReturn(List.of(c));

        List<SidecarRunPayload.Conn> out = resolver.resolveForAgent(1L);

        assertTrue(out.isEmpty(), "解析不了路径白名单时应跳过该连接：少一条连接看得见，多开的权限看不见");
    }

    @Test
    @DisplayName("allow_paths 合法时原样下发")
    void 路径白名单正常() {
        Connection c = row("HTTP", "https://api.example.com");
        c.setAllowPaths("[\"/v1/orders/**\"]");
        when(connectionMapper.selectList(any())).thenReturn(List.of(c));

        List<SidecarRunPayload.Conn> out = resolver.resolveForAgent(1L);

        assertEquals(List.of("/v1/orders/**"), out.get(0).getAllowPaths());
    }
}
