package com.jimeng.dataserver.ai.protocol;

import cn.hutool.json.JSONNull;

/**
 * 一个只解决一件事的工具类：<b>Hutool 解析出来的 JSON null 不是 Java null</b>。
 *
 * <h3>坑长什么样</h3>
 * {@code JSONUtil.toBean("{\"content\":null}", Map.class).get("content")} 拿到的是
 * {@link JSONNull} 单例，不是 {@code null}。于是：
 * <ul>
 *   <li>{@code o == null} 为 <b>false</b> —— 所有「取不到就跳过」的判空全部失效；</li>
 *   <li>{@code String.valueOf(o)} 得到<b>字符串 "null"</b> —— 它会被当成正常内容继续往下走。</li>
 * </ul>
 *
 * <h3>它真实造成过什么</h3>
 * {@code deepseek-reasoner} 每产生一个思考 token 就发一帧，帧里 {@code content} 是 null、
 * 真正的内容在 {@code reasoning_content}。上面两条叠加的结果是：界面上先刷出几百个连着的
 * {@code null}，<b>而且同样的 "null" 被累加进了要落库的助手消息</b>——下一轮对话会把这坨
 * 垃圾原样喂回模型。整个过程不抛异常、不打日志。
 *
 * <h3>为什么单独抽出来而不是各改各的</h3>
 * 三个协议适配器各有一个 {@code str()}，契约还不一样（有的缺省返回 {@code ""}，有的返回
 * {@code null}）。真正共用的不是「怎么转字符串」，而是<b>「什么算没有值」</b>这个判断——
 * 它在每个 JSON 入口都要做一次，而下一个写适配器的人默认会写 {@code o == null}。
 * 把判断连同这段说明放在一处，是唯一能让下一个人不再踩的办法。
 */
final class JsonNulls {

    private JsonNulls() {
    }

    /** 「这个字段等于没给」：Java null 与 JSON null 一视同仁。 */
    static boolean isNull(Object o) {
        return o == null || o instanceof JSONNull;
    }
}
