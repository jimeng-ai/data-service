package com.jimeng.dataserver.ai.agent.exec.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.dto.AgentExecRequest;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.agent.service.AgentRuntimeService;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.usage.UsageExtractor;
import com.jimeng.dataserver.ai.connector.agent.ConnectorAgentContextFactory;
import com.jimeng.dataserver.ai.connector.agent.ConnectorSkillMaterializer;
import com.jimeng.dataserver.ai.http.HttpGatewayProperties;
import com.jimeng.dataserver.ai.http.callback.HttpGatewayLeaseFactory;
import com.jimeng.dataserver.ai.http.callback.HttpGatewayRunRegistry;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.run.*;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.service.SkillBundleResolver;
import com.jimeng.dataserver.ai.skill.service.SkillTenantService;
import com.jimeng.persistence.entity.AgentExecRun;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentExecHttpLeaseTest {
    @ParameterizedTest
    @ValueSource(strings = {"closed", "exception", "timeout", "interrupted"})
    void dispatchesOnlyShippedGrantsAndRevokesBeforeResultPersistence(String outcome) {
        SidecarClient sidecar = mock(SidecarClient.class);
        AgentSandboxProperties props = new AgentSandboxProperties();
        // 等待预算为 0，直接验证超时分支，无真实墙钟等待。
        props.setWallClockSec(-30);
        AgentExecRunMapper runs = mock(AgentExecRunMapper.class);
        doAnswer(i -> { ((AgentExecRun) i.getArgument(0)).setId(91L); return 1; }).when(runs).insert(any(AgentExecRun.class));
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        when(runtime.byId(5L, false)).thenReturn(AgentRuntimeView.builder().agentId(5L).allowedSkillIds(Set.of(7L)).build());
        SkillTenantService skills = mock(SkillTenantService.class);
        AiSkill skill = new AiSkill(); skill.setId(7L); skill.setName("crm"); skill.setVersion(2);
        skill.setManifest(new SkillManifest(List.of("customer.test"), false, 60, null, null, List.of()).toJson());
        when(skills.listActiveDoerForRun("t1", 1L, Set.of(7L))).thenReturn(List.of(skill));
        SkillBundleResolver bundles = mock(SkillBundleResolver.class);
        SidecarRunPayload.SkillRef ref = new SidecarRunPayload.SkillRef(); ref.setName("crm");
        when(bundles.resolve(List.of(skill))).thenReturn(List.of(ref));
        ConnectorAgentContextFactory connector = new ConnectorAgentContextFactory(
                new com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties(),
                mock(com.jimeng.dataserver.ai.connector.service.ConnectorOverviewService.class),
                mock(com.jimeng.dataserver.ai.connector.agent.callback.ConnectorAgentRunRegistry.class),
                mock(AdminAuthService.class), sidecar,
                mock(com.jimeng.dataserver.ai.skill.service.SkillPackageLoaderService.class));
        HttpGatewayProperties httpProps = new HttpGatewayProperties(); httpProps.setCallbackBaseUrl("http://callback.test/data");
        HttpGatewayRunRegistry registry = mock(HttpGatewayRunRegistry.class);
        when(registry.begin(anyString(), anyString(), anyLong(), any(), anyLong())).thenReturn(true);
        AdminAuthService auth = mock(AdminAuthService.class);
        when(auth.mintHttpGatewayToken(any(), any(), any(), any(), anyLong())).thenReturn("RUN-CREDENTIAL-SENTINEL");
        HttpGatewayLeaseFactory factory = new HttpGatewayLeaseFactory(httpProps, registry, auth);
        RunFinalizer finalizer = mock(RunFinalizer.class);
        AgentExecService service = new AgentExecService(sidecar, mock(RunEventTee.class), finalizer, mock(RunRegistry.class), props,
                runs, mock(AgentInputFileMapper.class), mock(AgentArtifactMapper.class), mock(RagMinioStorageService.class), runtime,
                auth, mock(AiModelCallRecordService.class), mock(UsageExtractor.class), mock(ProviderRegistry.class),
                mock(PermissionResolver.class), skills, bundles, mock(ConnectorSkillMaterializer.class), connector, factory);
        AtomicReference<SidecarRunPayload> dispatched = new AtomicReference<>();
        when(sidecar.run(any(), any())).thenAnswer(i -> {
            dispatched.set(i.getArgument(0));
            if (outcome.equals("exception")) throw new IllegalStateException("dispatch failed");
            if (outcome.equals("closed")) ((EventSourceListener) i.getArgument(1)).onClosed(null);
            if (outcome.equals("interrupted")) Thread.currentThread().interrupt();
            return mock(EventSource.class);
        });
        TenantContext.set("t1"); AdminRequestContext.setAsyncUserId(1L);
        try {
            AgentExecRequest request = new AgentExecRequest(); request.setAgentId("5"); request.setQuery("q");
            service.streamExec(request, "conversation", "trace");
            assertThat(dispatched.get().getSkills()).extracting(SidecarRunPayload.SkillRef::getName).containsExactly("crm");
            assertThat(dispatched.get().getHttpContext().getHosts()).containsExactly("customer.test");
            assertThat(dispatched.get().getHttpContext().getAccessToken()).isEqualTo("RUN-CREDENTIAL-SENTINEL");
            assertThat(dispatched.get().toString()).doesNotContain("RUN-CREDENTIAL-SENTINEL");
            verify(registry).begin(eq("t1"), eq("91"), eq(5L), argThat(g -> g.size() == 1 && g.get(0).skillId().equals(7L)), anyLong());
            var order = inOrder(registry, runs, finalizer);
            order.verify(registry).end("t1", "91");
            order.verify(runs).updateById(any(AgentExecRun.class));
            order.verify(finalizer).complete("conversation");
        } finally {
            Thread.interrupted(); AdminRequestContext.clearAsyncUserId(); TenantContext.clear();
        }
    }
}
