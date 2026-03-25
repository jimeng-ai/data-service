package com.jimeng.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/7/11 22:02
 */

@Data
@Component
@ConfigurationProperties(prefix = "ignore")
public class AuthConfiguration {

    private AuthConfig auth = new AuthConfig();

    @Data
    public static class AuthConfig {
        private List<String> whitesUrl;
    }

}
