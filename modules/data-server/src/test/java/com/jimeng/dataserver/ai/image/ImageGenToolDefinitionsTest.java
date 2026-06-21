package com.jimeng.dataserver.ai.image;

import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImageGenToolDefinitionsTest {

    @Test
    void buildsGenerateImageDef() {
        SkillToolDefinition def = ImageGenToolDefinitions.GENERATE_IMAGE;
        assertEquals("generate_image", def.getModelName());
        Map<String, Object> schema = def.getInputSchema();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("prompt"));
        assertTrue(props.containsKey("count"));
        assertEquals(List.of("prompt"), schema.get("required"));
    }
}
