package com.jimeng.dataserver.ai.connector.runtime;

import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
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
 *
 * <h3>★ 本类不经 {@link ConnectorGateway}，直接连客户系统——「所有访问必经网关」的两个有文档的例外之一</h3>
 * {@link #probe} 自己 {@code connector.open()}。一次探测往客户系统发的东西：
 * <ul>
 *   <li><b>MySQL</b>：从连接池借连接（必要时先建池）；{@code SELECT 1}（探活）；
 *       {@code UPDATE <探针表> SET x = 1 WHERE 1 = 0}（验只读——<b>一次写尝试</b>，期望被权限拒绝）；
 *       一条 {@code information_schema.TABLES ... LIMIT 1}（探自描述能力）。至多三条语句。</li>
 *   <li><b>HTTP</b>：只有探活那一次对 base_url 的 {@code HEAD}。验只读看的是我们自己的方法白名单、能力是类型写死的，都不发请求。</li>
 * </ul>
 * 网关那几道闸这里<b>一道都没有</b>：不写 {@code connector_audit}、不占每租户速率桶、不抢每实例并发许可。
 * 客户 DBA 在自己库的审计日志里看得到那条 UPDATE 尝试，在我们的审计表里却找不到对应的一行——
 * 那条 UPDATE 的来历只能是：有人在管理台试连、新建、编辑或测试了这条连接。
 *
 * <p><b>为什么不走网关——不是没顾上，是网关无从下手：</b>
 * <ul>
 *   <li><b>试连（{@code ConnectorService.dryRun}）根本没有落库的行。</b>它探的是用表单参数现拼的实例，id 是合成的负数。
 *       网关按名字或 id 去 {@code connection} 表找行、从行上解析配置，找不到任何东西。</li>
 *   <li><b>新建、编辑要验的是还没落定的配置。</b>新建的行在同一个事务里刚插入、尚未提交；编辑的新参数在探测通过之前刻意不落库。
 *       网关的契约是「按库里那一行执行」，拿它去验一份没落定的配置，验到的是哪一份取决于事务可见性和 MyBatis 的一级缓存——
 *       「验过了」这个结论不能押在这种巧合上：押错的表现是旧配置通过了验证、新配置被保存，而且不报错。</li>
 *   <li><b>网关第 6 步看的 {@code capability_flags} 正是本类的产出。</b>新连接、从旧入口（{@code ConnectionService}）建的连接，
 *       这一列都是空的；网关对这种行的拒绝文案恰好是「请在管理台点一次『测试连接』」——而「测试连接」就是本类。
 *       让探测依赖它自己的结果，这些连接就永远探不出能力。</li>
 * </ul>
 *
 * <p><b>为什么可以接受：</b>它只由管理台上四个超管专属的动作触发（试连、新建、编辑、测试连接，见 {@code ConnectorAdminController}），
 * 是人手点击的频率，每次至多三条语句；结果落在连接行上（健康态、{@code readonly_verified_at}、{@code capability_flags}）。
 *
 * <p><b>由此两条不能破的规矩：</b>
 * <ul>
 *   <li><b>本类绝不能被定时任务或后台线程调用。</b>那条 UPDATE 尝试点一次无害，每 5 分钟一次就是客户 DBA 找上门——
 *       定时健康探测为此只调 {@code ping()}，见 {@code ConnectorHealthJob}。上面「可以接受」的理由全部建立在「人手点击」上。</li>
 *   <li><b>「本类不走网关」不是可以援引的先例。</b>它的理由是「网关无从寻址」；针对已落库连接的读结构、读数据、采样探查不满足这一条，
 *       一律走网关（后台任务走 {@link ConnectorGateway#executeAsPlatform}）。</li>
 * </ul>
 * 例外清单（网关、健康探测、本类）由单测 {@code ConnectorGatewayBypassInventoryTest} 从编译产物里钉住：
 * 谁在第四个地方调 {@code Connector.open}，那条测试会红。
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
            //
            // ★ 注意「跳过」和「按策略判定」是两回事：无论写策略是什么，这一步都【照跑】，
            //   因为它的结果要如实记进 readonly_verified_at 与界面。变的只是「结果决不决定能不能保存」。
            //   跳过会让一条 AUTO 的连接在界面上显示成「未验证」，而那其实是「没验过」和
            //   「验过是可写的」两种完全不同状态的混淆。
            ReadOnlyVerdict verdict;
            try {
                verdict = session.verifyReadOnly();
            } catch (ConnectorException e) {
                // 探测本身抛异常 ≠ 账号可写，但同样不能当成只读——归到「判不出来」。
                verdict = ReadOnlyVerdict.unknown("只读校验未能完成：" + safe(e));
            }
            if (verdict == null) {
                verdict = ReadOnlyVerdict.unknown("连接器没有返回只读校验结果");
            }

            WritePolicy policy = inst.writePolicy();
            if (!policy.allowsWrite() && !verdict.acceptable()) {
                // 只读策略下，账号必须确认是只读的。两种不通过要分开说：
                // 话术完全不同，客户要做的事也不同。
                String reason = verdict.undetermined()
                        ? "无法确认这个账号是只读的：" + verdict.detail()
                          + "。为安全起见不予保存——请确认账号权限后重试，或换一个明确只授予查询权限的账号。"
                        : "这个账号具备写权限：" + verdict.detail()
                          + "。当前连接的写策略是「只读」，请改用只读账号（数据库侧只 GRANT SELECT），"
                          + "或者在写策略里显式开放写操作。";
                return new ProbeReport(false, reason, verdict, Set.of());
            }
            if (policy.allowsWrite() && verdict.acceptable()) {
                // 开放了写，但客户给的是只读账号——不拦（连接本身能用，查询照跑），
                // 但必须如实告知：写操作会在【数据库】那一层被拒，而不是在平台这层。
                // 不说的话，用户会以为开关打开了写就能写，直到某次真写才发现。
                log.info("连接器写策略为 {} 但账号是只读的 connectorId={}：写操作将被数据库拒绝",
                        policy, inst.id());
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
            // 写能力是【策略 ∧ 类型支持 ∧ 账号确实能写】三者的交集，缺一不可：
            //   - 策略不开 → 平台侧就不放行
            //   - 类型不支持 → 比如 HTTP 连接器的写由 allowMethods 管，不走这条
            //   - 账号只读 → 平台放行了数据库也会拒，此时声明「能写」是在骗人
            // 三者都满足才回填 WRITE，界面与 conn_list 看到的就是真实可用的能力。
            caps = new java.util.LinkedHashSet<>(caps);
            boolean writable = policy.allowsWrite()
                    && connector.declaredCapabilities().contains(Capability.WRITE)
                    && !verdict.readOnly();
            if (writable) {
                caps.add(Capability.WRITE);
            } else {
                caps.remove(Capability.WRITE);
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
