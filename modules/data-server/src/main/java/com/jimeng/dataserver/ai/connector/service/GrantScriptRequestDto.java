package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.dataserver.ai.connector.spi.GrantRequest;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 「生成授权脚本」的 HTTP 入参。
 *
 * <p><b>为什么不直接收 {@link GrantRequest}：</b>那是个 record，而本仓库实际生效的
 * jackson-databind 是 2.11.1（由 logstash-logback-encoder:7.4 传递带入），
 * 反序列化 record 要 2.12+。同样的坑见 {@code GrantScript} 的类注释。
 *
 * <p>顺带的好处是入网形状与内部形状解耦：{@code writePolicy} 在这里是字符串
 * （前端 Select 出来的原始值），进 {@code GrantRequest} 时才收敛成枚举，
 * 且<b>认不出来一律当只读</b>——降级的后果是「脚本少授了权限」，客户会立刻发现；
 * 反过来多授的权限没人会回头收回去。
 */
@Schema(description = "生成授权脚本的入参")
@Data
public class GrantScriptRequestDto {

    @Schema(description = "连接器类型，如 MYSQL。由后端决定生成哪种方言，前端不认识具体类型")
    private String kind;

    @Schema(description = "库名")
    private String database;

    @Schema(description = "要创建的账号名")
    private String username;

    @Schema(description = "允许从哪登录：% 或具体 IP / 网段前缀。留空按 % 处理")
    private String host;

    @Schema(description = "要授权的表；空表示整库")
    private List<String> tables;

    @Schema(description = "写策略：FORBIDDEN | REQUIRE_APPROVAL | AUTO。决定授不授 INSERT/UPDATE/DELETE")
    private String writePolicy;

    public GrantRequest toRequest() {
        return new GrantRequest(database, username, host, tables, WritePolicy.parse(writePolicy));
    }
}
