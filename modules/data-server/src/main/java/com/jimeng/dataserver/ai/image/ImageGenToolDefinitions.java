package com.jimeng.dataserver.ai.image;

import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置生图工具定义（模型可见名 generate_image）。由 AiConversationLoop 在 agent.sandbox.image-gen
 * 三件套配齐时注入进请求体 tools 列表，对所有 Agent 永远在场（不走 skill 发现流程）。
 */
public final class ImageGenToolDefinitions {

    private ImageGenToolDefinitions() {
    }

    public static final SkillToolDefinition GENERATE_IMAGE = build();

    private static SkillToolDefinition build() {
        Map<String, Object> prompt = prop("string", "图片内容的详细文字描述（中英文均可）");
        Map<String, Object> count = prop("integer", "生成图片数量，1-4，默认 1");
        count.put("minimum", 1);
        count.put("maximum", 4);
        Map<String, Object> size = prop("string", "图片尺寸，默认 1024x1024");
        size.put("enum", List.of("1024x1024", "1536x1024", "1024x1536"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", prompt);
        properties.put("count", count);
        properties.put("size", size);
        return new SkillToolDefinition(
                "generate_image",
                "根据文字描述生成图片。当用户要求画图、生成图片、出图、做插画/头像，或在多轮里要求改图/换风格/换场景/重做/再来一张时，"
                        + "都必须调用本工具来真正出图——每出一张（含改版/重做）都要单独调用一次；"
                        + "绝不能只用文字声称「已生成/搞定」而不调用本工具。返回图片的可访问 URL。",
                objectSchema(properties, List.of("prompt")));
    }

    private static Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }
}
