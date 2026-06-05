package com.jimeng.dataserver.ai.rag.service.storage;

import cn.hutool.core.util.IdUtil;
import com.jimeng.common.core.configuration.FileConfiguration;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import io.minio.GetPresignedObjectUrlArgs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * RAG 专用 MinIO 存储服务。
 * RAG 在此自管 MinioClient（仅在被注入时才生效），不依赖任何全局 MinIO 工具类；
 * bucket 由 rag.ingestion.minio-bucket 控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagMinioStorageService {

    private static final DateTimeFormatter DATE_PREFIX = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final FileConfiguration fileConfiguration;
    private final RagProperties ragProperties;

    private MinioClient client;
    private String bucket;

    @PostConstruct
    public void init() {
        FileConfiguration.Minio cfg = fileConfiguration.getMinio();
        if (cfg == null || cfg.getEndpoint() == null) {
            log.warn("file.minio.* 未配置，RagMinioStorageService 暂未初始化，上传将失败");
            return;
        }
        client = MinioClient.builder()
                .endpoint(cfg.getEndpoint())
                .credentials(cfg.getAccessKey(), cfg.getSecretKey())
                .build();
        bucket = ragProperties.getIngestion().getMinioBucket();
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket [{}] 创建完成", bucket);
            }
        } catch (Exception e) {
            log.error("MinIO bucket 初始化失败: {}", e.getMessage(), e);
        }
    }

    public String upload(MultipartFile file) throws Exception {
        ensureReady();
        String objectName = buildObjectName(file.getOriginalFilename());
        try (InputStream is = file.getInputStream()) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .contentType(file.getContentType())
                    .stream(is, file.getSize(), -1)
                    .build());
        }
        log.info("MinIO upload bucket={} object={} size={}B", bucket, objectName, file.getSize());
        return objectName;
    }

    public String uploadBytes(byte[] bytes, String filename, String contentType) throws Exception {
        ensureReady();
        String objectName = buildObjectName(filename);
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .contentType(contentType)
                    .stream(is, bytes.length, -1)
                    .build());
        }
        return objectName;
    }

    public InputStream download(String objectName) throws Exception {
        ensureReady();
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectName).build());
    }

    public void delete(String objectName) throws Exception {
        ensureReady();
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
    }

    public String presignedUrl(String objectName, int expirySeconds) throws Exception {
        ensureReady();
        return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .bucket(bucket).object(objectName).method(Method.GET).expiry(expirySeconds).build());
    }

    public String getBucket() {
        return bucket;
    }

    private void ensureReady() {
        if (client == null) {
            throw new IllegalStateException("RagMinioStorageService 未初始化，请检查 file.minio.* 配置");
        }
    }

    private String buildObjectName(String filename) {
        String prefix = LocalDate.now().format(DATE_PREFIX);
        String safeName = filename == null ? "file" : filename.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]+", "_");
        return prefix + "/" + IdUtil.fastSimpleUUID() + "_" + safeName;
    }
}
