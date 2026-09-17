package com.jimeng.dataserver.ai.connector.generation;

/**
 * 语义层生成策略。
 *
 * <p>实现必须立即返回、不向调用方抛异常，也不在请求线程上访问客户库或模型。
 */
public interface SemanticGenerator {

    GeneratorKind kind();

    GenerationAck submit(GenerationRequest request);
}
