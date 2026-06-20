package com.jimeng.dataserver.ai.skill.controller.dto;

import lombok.Data;

/** DOER skill bundle 里的单个文件（用于管理页详情查看脚本）。 */
@Data
public class SkillFileView {
    /** 相对 bundle 前缀的路径，如 scripts/run.py */
    private String path;
    /** 文本内容；二进制文件为 null */
    private String content;
    /** 是否二进制（图片等），二进制不返回 content */
    private boolean binary;
    /** 内容是否因超过大小上限被截断 */
    private boolean truncated;
    /** 文件字节大小 */
    private long size;
}
