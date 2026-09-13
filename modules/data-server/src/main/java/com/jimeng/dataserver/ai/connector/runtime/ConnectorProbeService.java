package com.jimeng.dataserver.ai.connector.runtime;

import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 接入探测。<b>三步走的顺序由本类定死，连接器实现只填每一步</b>——这是这套框架里唯一一处
 * 真正用到 Template Method 的地方，它换来的是「任何一种新连接器都不可能跳过只读校验」。
 *
 * <h3>顺序是有意义的，不是排版</h3>
 * 探活 → <b>验只读</b> → 探能力。连不上就不必验只读（结果无意义）；没验过只读就不该进能力探测
 * （能力探测本身会真的去读客户的库）。反过来写，一个配错的可写账号会在被发现之前先被用一遍。
 *
 * <h3>★ 只验「好路径能通」是不够的</h3>
 * 必须同时验「坏路径被拦」。这条是从部署密钥那一轮栽的跟头里学到的：当时沙箱 token 被配成了
 * 一段 YAML 行内注释，匿名请求照样被拦（<b>看着像成功</b>），但正常派发也一起 401 了——
 * 只验了「该拦的拦住了」，没验「该通的通了」，于是错误配置披着成功的外衣活了下来。
 * 这里是同一条原则的镜像：{@link ConnectorSession#verifyReadOnly()} 要求
 * <b>试一个无害的写操作、期望它失败</b>，而不是「查一下能不能读」。
 *
 * <h3>判不出来 = 不通过</h3>
 * {@link ReadOnlyVerdict} 是三态：确认只读 / 确认可写 / <b>判不出来</b>。
 * 只有第一种放行。把「判不出来」当成只读，就等于没验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorProbeService {

    /**
     * @param ok            三步全过
     * @param failureReason 给人看的失败原因，已脱敏，可直接展示给客户
     * @param readOnly      只读判定；ping 就失败时为 null
     * @param capabilities  实际可用能力；前两步没过时为空集
     */
    public record ProbeReport(boolean ok, String failureReason, ReadOnlyVerdict readOnly, Set<Capability> capabilities) {}

    private final ConnectorRegistry registry;

    public ProbeReport probe(ConnectorInstance inst) {
        Connector connector = registry.require(inst.kind());

        // open() 本身就是第一道：连不上、凭据错、参数非法都在这里暴露。
        try (ConnectorSession session = connector.open(inst)) {

            // ---- 第一步：探活 ----
            try {
                session.ping();
            } catch (ConnectorException e) {
                return new ProbeReport(false, "连接测试失败：" + safe(e), null, Set.of());
            }

            // ---- 第二步：验只读（承重层，不可跳过）----
            ReadOnlyVerdict verdict;
            try {
                verdict = session.verifyReadOnly();
            } catch (ConnectorException e) {
                // 探测本身抛异常 ≠ 账号可写，但同样不能放行——归到「判不出来」。
                verdict = ReadOnlyVerdict.unknown("只读校验未能完成：" + safe(e));
            }
            if (verdict == null) {
                verdict = ReadOnlyVerdict.unknown("连接器没有返回只读校验结果");
            }
            if (!verdict.acceptable()) {
                // 两种不通过要分开说：话术完全不同，客户要做的事也不同。
                String reason = verdict.undetermined()
                        ? "无法确认这个账号是只读的：" + verdict.detail()
                          + "。为安全起见不予保存——请确认账号权限后重试，或换一个明确只授予查询权限的账号。"
                        : "这个账号具备写权限：" + verdict.detail()
                          + "。请改用只读账号（数据库侧只 GRANT SELECT）后重新保存。";
                return new ProbeReport(false, reason, verdict, Set.of());
            }

            // ---- 第三步：探能力 ----
            Set<Capability> caps;
            try {
                caps = session.probeCapabilities();
            } catch (ConnectorException e) {
                // 能力探不出来不是致命的——退回类型的静态声明，但要留日志。
                // 不退回会让一条本来可用的连接因为一次抖动被判死。
                log.warn("连接器能力探测失败，回退到类型声明 connectorId={} kind={}", inst.id(), inst.kind(), e);
                caps = connector.declaredCapabilities();
            }
            if (caps == null || caps.isEmpty()) {
                caps = connector.declaredCapabilities();
            }
            return new ProbeReport(true, null, verdict, caps);

        } catch (ConnectorException e) {
            return new ProbeReport(false, safe(e), null, Set.of());
        } catch (Exception e) {
            log.warn("连接器探测出现未归类异常 connectorId={} kind={}", inst.id(), inst.kind(), e);
            return new ProbeReport(false, "连接测试失败，请检查配置后重试", null, Set.of());
        }
    }

    /** 只取脱敏文案。原始异常在各实现的 classify 里已经被挡在日志侧了，这里再兜一层。 */
    private static String safe(ConnectorException e) {
        String detail = e.getSafeDetail();
        return detail == null || detail.isBlank() ? e.getCode().title() : detail;
    }
}
