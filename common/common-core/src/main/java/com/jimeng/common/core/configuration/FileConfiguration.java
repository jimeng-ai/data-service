package com.jimeng.common.core.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author Moonlight
 * @Description minio 参数配置
 * @Date 2024/7/25 23:08
 */

@Data
@Component
@ConfigurationProperties(prefix = "file")
public class FileConfiguration {

    private Minio minio = new Minio();

    @Data
    public static class Minio {
        private String endpoint;
        private String bucketName;
        private String accessKey;
        private String secretKey;
        private Integer imgSize;
        private Integer fileSize;
    }

}
