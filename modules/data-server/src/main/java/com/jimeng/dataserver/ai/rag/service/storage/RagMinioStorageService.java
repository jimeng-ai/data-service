package com.jimeng.dataserver.ai.rag.service.storage;

import cn.hutool.core.util.IdUtil;
import com.jimeng.common.core.configuration.FileConfiguration;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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

    /** 按指定 objectName(可含路径前缀) 写入字节，不改写 key（uploadBytes 会改写，故另加此方法）。 */
    public void putObject(String objectName, byte[] bytes, String contentType) throws Exception {
        ensureReady();
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectName).contentType(contentType)
                    .stream(is, bytes.length, -1).build());
        }
    }

    /** 列出某前缀下的所有对象 key（递归）。 */
    public List<String> listObjects(String prefix) throws Exception {
        ensureReady();
        List<String> keys = new ArrayList<>();
        Iterable<Result<Item>> results = client.listObjects(
                ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build());
        for (Result<Item> r : results) keys.add(r.get().objectName());
        return keys;
    }

    /** 对象的名字 / 大小 / 最后修改时间（listObjectInfos 用）。 */
    public record ObjectInfo(String name, long size, Date lastModified) {}

    /** 列出某前缀下的所有对象（递归），带大小与最后修改时间——Skill 构建器读工作区、找最新一轮评审页用。 */
    public List<ObjectInfo> listObjectInfos(String prefix) throws Exception {
        ensureReady();
        List<ObjectInfo> out = new ArrayList<>();
        Iterable<Result<Item>> results = client.listObjects(
                ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build());
        for (Result<Item> r : results) {
            Item it = r.get();
            if (it.isDir()) continue;
            Date modified = it.lastModified() == null ? null : Date.from(it.lastModified().toInstant());
            out.add(new ObjectInfo(it.objectName(), it.size(), modified));
        }
        return out;
    }

    /** 读整个对象（调用方负责只对有上限的小对象这样做）。 */
    public byte[] readBytes(String objectName) throws Exception {
        try (InputStream is = download(objectName)) {
            return is.readAllBytes();
        }
    }

    /** 服务端复制（同 bucket），不经本进程中转字节。 */
    public void copyObject(String sourceObject, String targetObject) throws Exception {
        ensureReady();
        client.copyObject(CopyObjectArgs.builder()
                .bucket(bucket).object(targetObject)
                .source(CopySource.builder().bucket(bucket).object(sourceObject).build())
                .build());
    }

    /**
     * 删除某前缀下的全部对象，返回删掉的个数。
     *
     * <p>★ MinIO Java SDK 的 removeObjects 是<b>惰性</b>的：返回的 Iterable 不迭代，一个对象都不会被删，
     * 而且不报错。这里必须把结果逐个取一遍（出错的对象会以 DeleteError 出现在里面）。
     */
    public int deletePrefix(String prefix) throws Exception {
        ensureReady();
        if (prefix == null || prefix.isBlank() || !prefix.endsWith("/")) {
            // 空前缀 = 整个 bucket；不以 / 结尾会误删同名前缀的兄弟（skills/1 会匹配 skills/10/…）。
            throw new IllegalArgumentException("deletePrefix 需要以 / 结尾的非空前缀: " + prefix);
        }
        List<DeleteObject> objects = new ArrayList<>();
        for (String key : listObjects(prefix)) objects.add(new DeleteObject(key));
        if (objects.isEmpty()) return 0;
        int failed = 0;
        for (Result<DeleteError> r : client.removeObjects(
                RemoveObjectsArgs.builder().bucket(bucket).objects(objects).build())) {
            DeleteError err = r.get();
            failed++;
            log.warn("MinIO 删除失败 object={} msg={}", err.objectName(), err.message());
        }
        return objects.size() - failed;
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
