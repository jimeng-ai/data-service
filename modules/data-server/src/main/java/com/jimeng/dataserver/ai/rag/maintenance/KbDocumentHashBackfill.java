package com.jimeng.dataserver.ai.rag.maintenance;

import cn.hutool.core.io.IoUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.KbDocument;
import com.jimeng.persistence.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 一次性回算 kb_document.file_hash 的迁移：旧实现用 {@code sha256(new String(bytes))}（先把二进制字节按默认字符集
 * 解码成 String 再哈希——非法字节塌缩成 U+FFFD，既增碰撞又依赖 JVM 编码），新实现改为对原始字节直接
 * {@code sha256(bytes)}。算法变了，存量哈希全部失效，重传老文件会去重失配、产生重复文档。本类把存量按新算法刷一遍。
 *
 * <p>自愈式、零运维：
 * <ul>
 *   <li><b>自动触发</b>：启动后台异步执行（不阻塞 Spring 启动 / 对外服务）。默认开启，可用
 *       {@code rag.backfill.file-hash-enabled=false} 关掉。</li>
 *   <li><b>只跑一次</b>：完成后写 Redis 标记（AOF 持久化），后续启动秒跳过；幂等——即便标记丢失重跑，
 *       重算出的哈希与现值相同 → 不会产生写入。</li>
 *   <li><b>多副本安全</b>：Redisson 锁（watchdog 续约）保证同一时刻只有一个实例在跑，其余直接跳过。</li>
 *   <li><b>跨租户</b>：{@link TenantContext#runAsSystem} 遍历所有租户的活跃文档。</li>
 * </ul>
 *
 * <p>容错：对象在存储中缺失 → 跳过并记日志；回写命中 {@code uk_kb_hash}（旧有损算法把同内容文件算成了不同哈希、
 * 新算法收敛到同一个）→ 判定为真重复，软删当前这条并记日志，不让整条任务崩。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbDocumentHashBackfill implements CommandLineRunner {

    /** 迁移完成标记键（Redis AOF 持久化）。 */
    private static final String DONE_KEY = "rag:migration:file_hash_sha256_bytes:done";
    /** 分布式锁键。 */
    private static final String LOCK_KEY = "rag:migration:file_hash_sha256_bytes:lock";
    /** 分页批大小：避免一次性把全表载入内存。 */
    private static final int BATCH = 200;

    private final KbDocumentMapper kbDocumentMapper;
    private final RagMinioStorageService storage;
    private final RedissonClient redisson;

    @Value("${rag.backfill.file-hash-enabled:true}")
    private boolean enabled;

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("[file_hash 回算] 已禁用（rag.backfill.file-hash-enabled=false），跳过");
            return;
        }
        RBucket<String> done = redisson.getBucket(DONE_KEY);
        if (done.isExists()) {
            log.info("[file_hash 回算] 已完成过，跳过");
            return;
        }
        // 后台异步，不阻塞启动与对外服务。
        Thread t = new Thread(this::runGuarded, "kb-filehash-backfill");
        t.setDaemon(true);
        t.start();
    }

    /** 抢锁 → 双检标记 → 跨租户回算 → 写标记。 */
    private void runGuarded() {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            // waitTime=0 + 默认 lease（watchdog 自动续约），抢不到说明别的实例在跑，直接退出。
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                log.info("[file_hash 回算] 另一实例正在执行，本实例跳过");
                return;
            }
            RBucket<String> done = redisson.getBucket(DONE_KEY);
            if (done.isExists()) {
                return; // 双重检查
            }
            int[] stat = TenantContext.runAsSystem(this::backfillAll);
            done.set("done@" + System.currentTimeMillis());
            log.warn("[file_hash 回算] 完成：更新 {} 条，跳过 {} 条，重复软删 {} 条，缺失对象 {} 条",
                    stat[0], stat[1], stat[2], stat[3]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[file_hash 回算] 被中断", e);
        } catch (Exception e) {
            log.error("[file_hash 回算] 异常终止（未写完成标记，下次启动会重试）", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** @return [updated, skipped, dupSoftDeleted, missing] */
    private int[] backfillAll() {
        int updated = 0, skipped = 0, dup = 0, missing = 0, scanned = 0;
        long lastId = 0L;
        while (true) {
            // 按 id 升序分页；@TableLogic 自动只取 deleted=0。
            LambdaQueryWrapper<KbDocument> qw = new LambdaQueryWrapper<KbDocument>()
                    .select(KbDocument::getId, KbDocument::getFileHash, KbDocument::getMinioObject)
                    .gt(KbDocument::getId, lastId)
                    .orderByAsc(KbDocument::getId)
                    .last("limit " + BATCH);
            List<KbDocument> page = kbDocumentMapper.selectList(qw);
            if (page.isEmpty()) {
                break;
            }
            for (KbDocument doc : page) {
                lastId = doc.getId();
                scanned++;
                String newHash = recompute(doc.getMinioObject());
                if (newHash == null) {
                    missing++;
                    continue;
                }
                if (newHash.equals(doc.getFileHash())) {
                    skipped++; // 已是新算法（幂等重跑会走到这）
                    continue;
                }
                try {
                    kbDocumentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                            .eq(KbDocument::getId, doc.getId())
                            .set(KbDocument::getFileHash, newHash));
                    updated++;
                } catch (DuplicateKeyException dke) {
                    // 同 kb 内已有另一条是该内容的新哈希 → 当前这条是真重复，软删之。
                    kbDocumentMapper.deleteById(doc.getId());
                    dup++;
                    log.warn("[file_hash 回算] docId={} 命中 uk_kb_hash（内容重复），已软删", doc.getId());
                }
            }
            log.info("[file_hash 回算] 进度：已扫描 {} 条（更新 {} / 跳过 {} / 重复 {} / 缺失 {}）",
                    scanned, updated, skipped, dup, missing);
        }
        return new int[]{updated, skipped, dup, missing};
    }

    /** 从对象存储读字节，按新算法算哈希；对象缺失或读失败返回 null。 */
    private String recompute(String objectName) {
        if (objectName == null || objectName.isEmpty()) {
            return null;
        }
        try (InputStream in = storage.download(objectName)) {
            byte[] bytes = IoUtil.readBytes(in);
            return DigestUtil.sha256Hex(bytes);
        } catch (Exception e) {
            log.warn("[file_hash 回算] 读对象失败，跳过 object={}：{}", objectName, e.getMessage());
            return null;
        }
    }
}
