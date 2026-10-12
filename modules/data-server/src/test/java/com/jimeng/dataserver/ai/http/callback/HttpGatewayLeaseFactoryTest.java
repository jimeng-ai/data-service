package com.jimeng.dataserver.ai.http.callback;

import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.http.HttpGatewayProperties;
import com.jimeng.dataserver.ai.http.SkillGrant;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.persistence.entity.AiSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpGatewayLeaseFactoryTest {

    private final HttpGatewayProperties props = new HttpGatewayProperties();
    private final HttpGatewayRunRegistry registry = mock(HttpGatewayRunRegistry.class);
    private final AdminAuthService auth = mock(AdminAuthService.class);
    private final HttpGatewayLeaseFactory factory = new HttpGatewayLeaseFactory(props, registry, auth);

    @BeforeEach
    void setUp() {
        props.setCallbackBaseUrl("http://localhost:10011/data");
        when(registry.begin(anyString(), anyString(), anyLong(), any(), anyLong())).thenReturn(true);
        when(auth.mintHttpGatewayToken(any(), any(), any(), any(), anyLong())).thenReturn("TOKEN-SENTINEL");
    }

    private static AiSkill skill(long id, String name, SkillManifest m) {
        AiSkill s = new AiSkill();
        s.setId(id);
        s.setName(name);
        s.setVersion(2);
        s.setManifest(m.toJson());
        return s;
    }

    private static final SkillManifest CRM = new SkillManifest(List.of("cdn.example.com"), false, 90, null, null, List.of(
            new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.customer.com"), List.of("GET"), null)));
    private static final SkillManifest WEB = new SkillManifest(List.of(), true, null, null, null, List.of());

    @Test
    @DisplayName("下发的 skill 里有 HTTP 声明：先登记再签发；上下文里有 key 的声明（带 skill 名）、域名并集、web、最大超时")
    void granted() {
        HttpGatewayLeaseFactory.Lease lease = factory.open("t1", 1L, 5L, "r1", 300,
                List.of(skill(7L, "crm", CRM), skill(8L, "news", WEB), skill(9L, "plain", SkillManifest.EMPTY)));
        assertThat(lease.granted()).isTrue();
        SidecarRunPayload.HttpContext c = lease.context();
        assertThat(c.getCallbackBaseUrl()).isEqualTo("http://localhost:10011/data");
        assertThat(c.getAccessToken()).isEqualTo("TOKEN-SENTINEL");
        assertThat(c.getTimeoutSeconds()).isEqualTo(90);
        assertThat(c.getWeb()).isTrue();
        assertThat(c.getHosts()).containsExactly("cdn.example.com");
        assertThat(c.getKeys()).singleElement().satisfies(k -> {
            assertThat(k.getSkill()).isEqualTo("crm");
            assertThat(k.getName()).isEqualTo("CRM_TOKEN");
            assertThat(k.getMethods()).containsExactly("GET");
            assertThat(k.getPaths()).isNull();
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillGrant>> grants = ArgumentCaptor.forClass(List.class);
        verify(registry).begin(eq("t1"), eq("r1"), eq(5L), grants.capture(), eq(AdminAuthService.httpGatewayTokenTtlMs(300)));
        assertThat(grants.getValue()).extracting(SkillGrant::skillName).containsExactly("crm", "news");
        assertThat(grants.getValue()).allMatch(g -> g.version() == 2);
        lease.close();
        lease.close();
        verify(registry, times(1)).end("t1", "r1");
    }

    @Test
    void signingFailureRevokesRegistrationWithoutExposingCause() {
        when(auth.mintHttpGatewayToken(any(), any(), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("TOKEN-SENTINEL"));
        HttpGatewayLeaseFactory.Lease lease = factory.open("t1", 1L, 5L, "r1", 300,
                List.of(skill(7L, "crm", CRM)));
        assertThat(lease.granted()).isFalse();
        assertThat(lease.declineReason()).doesNotContain("TOKEN-SENTINEL");
        lease.close();
        var order = inOrder(registry, auth);
        order.verify(registry).begin(eq("t1"), eq("r1"), eq(5L), any(), anyLong());
        order.verify(auth).mintHttpGatewayToken(eq(1L), eq("t1"), eq(5L), eq("r1"), anyLong());
        order.verify(registry).end("t1", "r1");
        verify(registry, times(1)).end("t1", "r1");
    }

    @Test
    @DisplayName("不下发的几种情况：没有 HTTP 声明、没配回调地址、缺用户、登记失败——都不签 token")
    void declined() {
        assertThat(factory.open("t1", 1L, 5L, "r1", 300, List.of(skill(9L, "plain", SkillManifest.EMPTY))).granted()).isFalse();
        assertThat(factory.open("t1", null, 5L, "r1", 300, List.of(skill(7L, "crm", CRM))).granted()).isFalse();
        when(registry.begin(anyString(), anyString(), anyLong(), any(), anyLong())).thenReturn(false);
        assertThat(factory.open("t1", 1L, 5L, "r1", 300, List.of(skill(7L, "crm", CRM))).granted()).isFalse();
        props.setCallbackBaseUrl("");
        HttpGatewayLeaseFactory.Lease noUrl = factory.open("t1", 1L, 5L, "r1", 300, List.of(skill(7L, "crm", CRM)));
        assertThat(noUrl.granted()).isFalse();
        assertThat(noUrl.declineReason()).contains("callback-base-url");
        noUrl.close();
        verify(auth, never()).mintHttpGatewayToken(any(), any(), any(), any(), anyLong());
        verify(registry, never()).end(any(), any());
    }
}
