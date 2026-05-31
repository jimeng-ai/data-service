package com.jimeng.dataserver.ai.agent.exec.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** /data/agent/files 上传返回。 */
@Schema(description = "Agent 输入文件上传结果")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AgentFileView {
    private Long fileId;
    private String objectName;
    private String bucket;
    private String filename;
    private String contentType;
    private Long sizeBytes;
}
