package com.jimeng.dataserver.ai.feedback.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.ProductFeedbackImage;
import com.jimeng.persistence.mapper.ProductFeedbackImageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 清理「传了图没提交」的草稿图：feedback_id 仍为 NULL 且超过 24h 宽限的，删 MinIO 对象 + 删行。
 * 跨租户操作，用 TenantContext.runAsSystem 跳过多租户过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackImageCleanupJob {

    private static final long GRACE_MS = 24L * 60 * 60 * 1000; // 24h

    private final ProductFeedbackImageMapper imageMapper;
    private final RagMinioStorageService storage;

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L) // 每小时，启动后 10min 起跑
    public void sweep() {
        TenantContext.runAsSystem(() -> {
            Date cutoff = new Date(System.currentTimeMillis() - GRACE_MS);
            List<ProductFeedbackImage> orphans = imageMapper.selectList(
                    new LambdaQueryWrapper<ProductFeedbackImage>()
                            .isNull(ProductFeedbackImage::getFeedbackId)
                            .lt(ProductFeedbackImage::getCreateTime, cutoff));
            int deleted = 0;
            for (ProductFeedbackImage img : orphans) {
                try {
                    storage.delete(img.getObjectKey());
                } catch (Exception e) {
                    log.warn("清理草稿图 MinIO 删除失败 objectKey={}: {}", img.getObjectKey(), e.getMessage());
                    continue; // 删对象失败则保留行，下轮重试
                }
                imageMapper.deleteById(img.getId());
                deleted++;
            }
            if (deleted > 0) {
                log.info("反馈草稿图清理：删除 {} 张超 24h 未引用图片", deleted);
            }
            return null;
        });
    }
}
