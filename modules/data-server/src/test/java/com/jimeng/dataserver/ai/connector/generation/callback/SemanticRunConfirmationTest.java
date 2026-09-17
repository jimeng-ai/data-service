package com.jimeng.dataserver.ai.connector.generation.callback;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 设计文档 7.2：回调一到达就用独立事务留下运行确认，不随后续 4000/5007 回滚。 */
class SemanticRunConfirmationTest {

    private ConnectorSemanticGenerationMapper generationMapper;
    private SemanticRunConfirmation confirmation;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ConnectorSemanticGeneration.class);
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        confirmation = new SemanticRunConfirmation(generationMapper);
    }

    @Test
    @DisplayName("确认以 REQUIRES_NEW 提交，并绑定 tenant/gen/cid/RUNNING/runId")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void 独立事务与完整CAS条件() throws Exception {
        when(generationMapper.update(any(), any())).thenReturn(1);
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        transactionalProxy(transactions).confirm(principal());

        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        Transactional annotation = SemanticRunConfirmation.class.getMethod(
                "confirm", SemanticAgentPrincipal.class).getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation());

        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemanticGeneration>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(generationMapper).update(any(), captor.capture());
        assertEquals("current_run_callbacks = current_run_callbacks + 1", captor.getValue().getSqlSet());
        String where = captor.getValue().getSqlSegment();
        assertTrue(where.contains("tenant_id"), where);
        assertTrue(where.contains("connector_id"), where);
        assertTrue(where.contains("status"), where);
        assertTrue(where.contains("current_run_id"), where);
        assertTrue(where.contains("id"), where);
    }

    @Test
    @DisplayName("确认 CAS 影响零行回 4090 并回滚独立事务")
    void cas零行() {
        when(generationMapper.update(any(), any())).thenReturn(0);
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> transactionalProxy(transactions).confirm(principal()));

        assertEquals(ExceptionCode.SEMANTIC_GENERATION_CLOSED.getResultCode(), ex.getRespCode());
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    private static SemanticAgentPrincipal principal() {
        return new SemanticAgentPrincipal("tenant-a", "42", 9101L, 9201L, 3, "run-3", "DIRECT");
    }

    private SemanticRunConfirmation transactionalProxy(RecordingTransactionManager transactionManager) {
        ProxyFactory factory = new ProxyFactory(confirmation);
        factory.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        return (SemanticRunConfirmation) factory.getProxy();
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 只验证 Spring 事务拦截器确实读取注解并走 commit / rollback 分支。
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
