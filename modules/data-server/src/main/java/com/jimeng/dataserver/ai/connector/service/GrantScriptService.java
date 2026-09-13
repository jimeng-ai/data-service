package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.GrantRequest;
import com.jimeng.dataserver.ai.connector.spi.GrantScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 按表单参数生成「客户 IT 可以直接复制执行」的授权脚本。
 *
 * <h3>这个 service 为什么这么薄</h3>
 * 方言知识<b>全部</b>在 {@link Connector#grantScript} 里，这里只做三件跨类型都一样的事：
 * 找连接器、把「这种类型不提供」翻译成一个业务错误、把连接器的异常翻译成管理台能看懂的话。
 * <b>薄是目标不是遗憾</b>——一旦这里长出 {@code if ("MYSQL".equals(kind))} 之类的分支，
 * 就等于把方言知识从连接器里漏了出来，那条「新增类型只改一个实现类」的验收标准也就破了。
 *
 * <h3>不连库、不落库、不碰租户</h3>
 * 生成脚本是<b>纯函数</b>：输入是表单里那几个字段，输出是一段文本。
 * 它发生在连接建立<b>之前</b>（客户还没有账号，正是要靠这段脚本去建），
 * 所以既不需要 connectorId，也不该去 load 任何实例。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrantScriptService {

    private final ConnectorRegistry registry;

    /**
     * @param kind 连接器类型（大小写与首尾空格由注册表归一）
     * @throws ServiceException kind 不支持、或该类型不提供脚本（{@code OPERATION_UNSUPPORTED}）；
     *                         参数非法（{@code INVALID_REQUEST}）
     */
    public GrantScript generate(String kind, GrantRequest req) {
        // 刻意用 find 而不是 registry.require()：require 抛的是 ConnectorException，
        // 而它没有被 GlobalExceptionHandler 认领，出网会变成一句「服务器内部错误」——
        // 类型选错是用户能自己修的事，必须如实告诉他。
        Connector connector = registry.find(kind).orElseThrow(() -> new ServiceException(
                ExceptionCode.OPERATION_UNSUPPORTED, "不支持的连接器类型 " + kind + "，无法生成授权脚本"));

        GrantScript script;
        try {
            script = connector.grantScript(req);
        } catch (ConnectorException e) {
            // 已归一、已脱敏，直接转成业务异常给管理台看；原始异常只进日志。
            // 这里最常见的就是「库名含有非法字符」——那不是故障，是必须让人看见的拒绝理由。
            log.warn("生成授权脚本失败 kind={}", kind, e);
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    e.getSafeDetail() == null ? e.getCode().title() : e.getSafeDetail());
        }

        // null 是 SPI 约定的「本类型不提供」（如 HTTP 连接器：授权发生在对方系统里，没有一段 SQL 可给）。
        // 顺手把「返回了空脚本」也归到这一档：给界面一个空代码框，比明说不提供更让人摸不着头脑。
        if (script == null || script.getSql() == null || script.getSql().isBlank()) {
            throw new ServiceException(ExceptionCode.OPERATION_UNSUPPORTED,
                    connector.displayName() + " 暂不提供授权脚本，请按界面上的权限说明在对方系统里手工授权");
        }
        return script;
    }
}
