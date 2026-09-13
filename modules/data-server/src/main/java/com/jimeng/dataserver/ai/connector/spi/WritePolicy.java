package com.jimeng.dataserver.ai.connector.spi;

import java.util.Locale;

/**
 * 一条连接的<b>写操作开放程度</b>。对应产品方案第 8 节的能力分级。
 *
 * <p>这是整套接入层里<b>风险最高</b>的一个开关：它决定 Agent 能不能改客户的生产业务数据，
 * 而出错的后果按产品方案的原话是「业务事故」。所以：
 *
 * <ul>
 *   <li><b>默认值只能是 {@link #FORBIDDEN}</b>。「授权的默认值只能是否」——
 *       新建连接、存量行回填、解析不出来的值，一律落到这一档。</li>
 *   <li>改这个值<b>限企业超管</b>。开放写权限比建一条连接更重：建连接是「能查什么」，
 *       改这个是「能改什么」。</li>
 *   <li>它<b>只放宽平台侧的闸，放宽不了客户侧的账号权限</b>。选了 {@link #AUTO} 但客户给的
 *       还是只读账号，写操作照样会被<b>数据库</b>拒绝——这是对的，承重层本来就在那边。</li>
 * </ul>
 *
 * <h3>为什么 REQUIRE_APPROVAL 是最该被推荐的一档</h3>
 * {@link #AUTO} 的失败是无人察觉的：模型写错一条 UPDATE，数据就改了，没人知道。
 * {@link #REQUIRE_APPROVAL} 把「生成操作」和「执行操作」拆开，中间插一个人——
 * 产品方案第 8 节对这一档的风险评注是「<b>有人兜底</b>」，对 AUTO 是「业务事故」。
 */
public enum WritePolicy {

    /** 只读。任何写操作在平台侧就被拒绝，连发到客户库的机会都没有。 */
    FORBIDDEN,

    /** 写需审批：Agent 生成操作请求，落进待审批队列，超管点确认后才真正执行。 */
    REQUIRE_APPROVAL,

    /** 写自动：Agent 直接改客户业务数据。<b>需显式开启，且应当是例外而不是常态。</b> */
    AUTO;

    /**
     * 宽松解析。<b>认不出来一律落到 {@link #FORBIDDEN}</b>——
     * 存量行、手工改库、将来新增的枚举值，任何「不确定」的情形都必须往严的方向回落。
     * 往宽的方向回落就是本项目反复吃亏的那类静默降级，而这里降级的后果是「能改客户的数据」。
     */
    public static WritePolicy parse(String v) {
        if (v == null || v.isBlank()) {
            return FORBIDDEN;
        }
        try {
            return valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FORBIDDEN;
        }
    }

    public boolean allowsWrite() {
        return this != FORBIDDEN;
    }

    /** 给人看的短名。 */
    public String label() {
        return switch (this) {
            case FORBIDDEN -> "只读";
            case REQUIRE_APPROVAL -> "写需审批";
            case AUTO -> "写自动";
        };
    }
}
