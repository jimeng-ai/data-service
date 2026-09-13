package com.jimeng.dataserver.ai.connector.spi;

import java.util.List;

/**
 * 「请给我一段授权脚本」的输入。
 *
 * <p><b>它存在的理由不是技术，是接入成本。</b>界面上原来只写着「必须是只读账号
 * （数据库侧只 GRANT SELECT）」，客户 IT 得自己想怎么写，写完常常授多了（图省事给 {@code *.*}）
 * 或者授少了（漏了某张表），然后一来一回半天。产品方案第 4 节说得很直白：
 * 瓶颈从来不是「支持更多协议」，是<b>每次接入的边际成本</b>。把命令直接生成出来，
 * 这一来一回就没了。
 *
 * <p>是 record：它只在后端内部流转（controller 收 DTO → 转成它 → 交给连接器），
 * 不作为 HTTP 响应体出网，所以不受 jackson 2.11.1 序列化不了 record 那条限制。
 *
 * @param database    库名
 * @param username    要创建的账号名
 * @param host        允许从哪登录：{@code %}（任意地址）或具体 IP / 网段前缀
 * @param tables      要授权的表；<b>空表示整库</b>
 * @param writePolicy 决定授哪些权限——这是「平台侧的闸」与「数据库侧的授权」第一次对齐的地方
 */
public record GrantRequest(
        String database,
        String username,
        String host,
        List<String> tables,
        WritePolicy writePolicy
) {

    public GrantRequest {
        tables = tables == null ? List.of() : List.copyOf(tables);
        // ★ 写策略认不出来一律当只读。与 WritePolicy.parse() 同一条纪律：
        // 这里降级的后果是「脚本多授了写权限」，而多授的权限没人会回头收回去。
        writePolicy = writePolicy == null ? WritePolicy.FORBIDDEN : writePolicy;
        // host 留空默认 '%'：MySQL 里 host 段为空的账号没有实际意义，与其为此再跟客户来回一轮，
        // 不如给最常用的值 + 在 notes 里永远提醒「能收紧就收紧」。
        host = host == null || host.isBlank() ? "%" : host.trim();
        database = database == null ? null : database.trim();
        username = username == null ? null : username.trim();
    }
}
