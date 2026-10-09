package com.jimeng.dataserver.ai.skill.manifest;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 改进已有 skill 时，上一版的 key（设计文档 §3.4 第 4 条）。发布处理器是纯函数、不碰存储，要用上一版的东西，由调用方
 * （实现在 {@code SkillSecretService.previousKeys}）递过来。
 *
 * <p>为什么处理器要用：
 * <ul>
 *   <li>secrets.json 里不带 value 的条目是「沿用上一版的值」。这些条目没有原文可抹，改进会话里模型又把 key 贴进脚本时，
 *       原文就进了 bundle——所以发布时要把上一版的值解密出来，并进替换、二进制检查、值↔声明检查和消息抹值；</li>
 *   <li>文件里还在用 {@code {{X}}}、上一版有 X、这一版的 secrets.json 却没有 X 了，要拒绝发布（否则新版本静默丢 key）。</li>
 * </ul>
 */
public interface PreviousKeys {

    /** 新建 skill、导入：没有上一版。 */
    PreviousKeys NONE = new PreviousKeys() {
        @Override
        public Set<String> names() {
            return Set.of();
        }

        @Override
        public Map<String, String> values(Collection<String> names) {
            return Map.of();
        }
    };

    /** 上一版所有 key 的名字（不需要密钥）。 */
    Set<String> names();

    /**
     * 把这些名字在上一版的值解密出来；上一版没有的名字不出现在结果里（由 {@code SkillSecretService.prepare} 报「没有给值」）。
     * ★ 解密不了（没配密钥、密文解不开）必须抛 {@code ServiceException}，不能返回空 map 冒充「没有」：
     * 调用方拿它核对要发布的文件，跳过核对就等于放行。
     */
    Map<String, String> values(Collection<String> names);
}
