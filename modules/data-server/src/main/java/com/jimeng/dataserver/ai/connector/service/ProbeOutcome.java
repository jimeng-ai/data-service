package com.jimeng.dataserver.ai.connector.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一次<b>试连</b>的结果。用于「还没保存，先看看配得对不对」。
 *
 * <p>刻意把只读判定的三态原样带出来，而不是压成一个布尔：
 * 「确认只读」「确认可写」「判不出来」三者对客户意味着完全不同的动作
 * （可以保存 / 换只读账号 / 去查账号权限），压成布尔就等于让界面替客户做了这个判断。
 *
 * <p>不是 record：本仓库实际生效的 jackson-databind 是 2.11.1，record 序列化要 2.12+。
 */
@Schema(description = "连接器试连结果")
@Data
@Builder
public class ProbeOutcome {

    @Schema(description = "三步全过才是 true：连得上 + 确认只读 + 探到能力")
    private boolean ok;

    @Schema(description = "失败原因，已脱敏且写成可操作的句子，直接展示给客户")
    private String failureReason;

    @Schema(description = "探测到的【实际】可用能力。ok=false 时为空")
    private List<String> capabilities;

    @Schema(description = "是否确认为只读账号")
    private boolean readonlyVerified;

    @Schema(description = "只读判定【无法确定】——它与「确认可写」是两回事，两者都不予保存，但话术不同")
    private boolean readonlyUndetermined;

    @Schema(description = "只读判定的依据说明")
    private String readonlyDetail;
}
