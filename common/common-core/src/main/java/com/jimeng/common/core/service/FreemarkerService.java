package com.jimeng.common.core.service;

import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;
import org.springframework.web.servlet.view.freemarker.FreeMarkerConfigurer;

import java.io.StringReader;

/**
 * @Author Moonlight
 * @Description freemarker公共服务
 * @Date 2024/11/18 22:53
 */

@Service
@RequiredArgsConstructor
public class FreemarkerService {

    private final FreeMarkerConfigurer freeMarkerConfigurer;

    @SneakyThrows
    public String getFreemarker(String templateName, String templateText, Object templateData) {
        Configuration configuration = freeMarkerConfigurer.getConfiguration();
        Template template = new Template(templateName, new StringReader(templateText), configuration);
        return FreeMarkerTemplateUtils.processTemplateIntoString(template, templateData);
    }

}
