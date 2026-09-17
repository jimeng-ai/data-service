package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticSqlCorpusReader;
import com.jimeng.dataserver.ai.connector.service.SemanticValueProfiler;
import com.jimeng.dataserver.ai.connector.service.TableShapeDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 1 档 agent 路径的架构护栏：生成骨架只能读取结构元数据，不得接入采样验证或 SQL 语料。
 */
class AgentPathTierOneInvariantTest {

    private static final String GENERATION_PACKAGE =
            "com.jimeng.dataserver.ai.connector.generation";

    private static final Set<Class<?>> FORBIDDEN_TIER_ONE_DEPENDENCIES = Set.of(
            SemanticJoinValidator.class,
            TableShapeDetector.class,
            SemanticValueProfiler.class,
            SemanticSqlCorpusReader.class);

    @Test
    @DisplayName("generation包不注入采样验证与SQL语料相关类")
    void generation包不注入采样验证与SQL语料相关类() throws ClassNotFoundException {
        Set<String> violations = new LinkedHashSet<>();
        for (Class<?> beanType : generationSpringBeans()) {
            for (Constructor<?> constructor : beanType.getDeclaredConstructors()) {
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    if (FORBIDDEN_TIER_ONE_DEPENDENCIES.contains(parameterType)) {
                        violations.add(beanType.getName() + " -> " + parameterType.getName());
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), "第 1 档 agent 路径出现越界依赖: " + violations);
    }

    @Test
    @DisplayName("只有SingleCallSemanticGenerator注入推导服务")
    void 只有SingleCallSemanticGenerator注入推导服务() throws ClassNotFoundException {
        Set<Class<?>> consumers = new LinkedHashSet<>();
        for (Class<?> beanType : generationSpringBeans()) {
            for (Constructor<?> constructor : beanType.getDeclaredConstructors()) {
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    if (parameterType == ConnectorSemanticDeriveService.class) {
                        consumers.add(beanType);
                    }
                }
            }
        }

        assertEquals(Set.of(SingleCallSemanticGenerator.class), consumers);
    }

    private static Set<Class<?>> generationSpringBeans() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class, true));

        Set<Class<?>> beans = new LinkedHashSet<>();
        for (var candidate : scanner.findCandidateComponents(GENERATION_PACKAGE)) {
            beans.add(Class.forName(candidate.getBeanClassName()));
        }
        return beans;
    }
}
