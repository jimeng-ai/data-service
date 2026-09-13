package com.jimeng.dataserver.ai.connector.spi;

import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;

import java.util.Set;

/**
 * 一次已建立的连接器会话。能力以「实现哪些 {@code *Capable} 接口」表达——
 * 这就是「类型 × 能力正交」在代码里的落法：类型决定实现类，能力决定它实现了哪几个接口。
 *
 * <p>三个探测方法由框架按<b>固定顺序</b>调用（探活 → 验只读 → 探能力），实现方只填每一步，
 * 不能跳过、不能换序。顺序是有意义的：连不上就不必验只读，没验过只读就不该让它进能力探测。
 */
public interface ConnectorSession extends AutoCloseable {

    /**
     * 探活。连得上就正常返回，连不上抛 {@code ConnectorException}。
     * 必须是<b>便宜</b>的操作（{@code SELECT 1} / {@code HEAD /}），会被定期调用。
     */
    void ping();

    /**
     * 验证这把凭据确实是只读的：<b>试一个无害的写操作，期望它失败</b>。
     *
     * <p>这是「只读」三层防线里<b>唯一承重</b>的那一层。另外两层——我们自己的语句校验、
     * 提示词里的「请只写 SELECT」——前者可能被方言绕过，后者什么都挡不住。
     *
     * <p>只验「好路径能通」是不够的，必须同时验「坏路径被拦」。这条是从部署密钥那一轮
     * 栽的跟头里学到的：当时 token 被配成一段 YAML 行内注释，匿名请求照样被拦（看着像成功），
     * 但正常派发也一起 401 了，只有跑真实派发才暴露。
     *
     * <p>判不出来要返回 {@link ReadOnlyVerdict#unknown}，<b>不要返回 confirmed</b>——
     * 「不知道」当成「只读」就等于没验。
     */
    ReadOnlyVerdict verifyReadOnly();

    /**
     * 探测这个实例<b>实际</b>可用的能力。
     * 结果回填到实例的 {@code capability_flags}，界面如实显示哪项不可用、原因是什么。
     */
    Set<Capability> probeCapabilities();

    @Override
    void close();
}
