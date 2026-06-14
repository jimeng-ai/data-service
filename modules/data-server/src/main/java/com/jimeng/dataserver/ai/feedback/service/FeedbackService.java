package com.jimeng.dataserver.ai.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.feedback.dto.FeedbackDetail;
import com.jimeng.dataserver.ai.feedback.dto.FeedbackImageView;
import com.jimeng.dataserver.ai.feedback.dto.FeedbackListItem;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.ProductFeedback;
import com.jimeng.persistence.entity.ProductFeedbackImage;
import com.jimeng.persistence.mapper.ProductFeedbackImageMapper;
import com.jimeng.persistence.mapper.ProductFeedbackMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 产品反馈核心逻辑：图片上传、提交、查询、流式回传。复用 RagMinioStorageService 存图。 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final RagMinioStorageService storage;
    private final ProductFeedbackMapper feedbackMapper;
    private final ProductFeedbackImageMapper imageMapper;
    private final PermissionResolver permissionResolver;

    /** 单图上传：存 MinIO + 插一条 feedback_id=NULL 的草稿图行，返回 imageId。 */
    public ProductFeedbackImage uploadImage(MultipartFile file) throws Exception {
        FeedbackValidator.validateImage(file.getContentType(), file.getSize());
        String objectKey = storage.upload(file);
        ProductFeedbackImage img = new ProductFeedbackImage();
        img.setFeedbackId(null);
        img.setTenantId(TenantContext.get());
        img.setObjectKey(objectKey);
        img.setContentType(file.getContentType());
        img.setFileSize(file.getSize());
        img.setSortOrder(0);
        imageMapper.insert(img); // create_user/create_time 自动填充
        return img;
    }

    /** 提交反馈：建主记录 + 回填图片 feedback_id/sort_order（校验属主且未被引用）。 */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Integer feedbackType, String content, List<Long> imageIds) {
        int count = imageIds == null ? 0 : imageIds.size();
        FeedbackValidator.validateSubmit(feedbackType, content, count);

        ProductFeedback fb = new ProductFeedback();
        fb.setTenantId(TenantContext.get());
        fb.setFeedbackType(feedbackType);
        fb.setContent(content.trim());
        feedbackMapper.insert(fb);

        String me = permissionResolver.currentOwnerId();
        if (imageIds != null) {
            for (int i = 0; i < imageIds.size(); i++) {
                Long imageId = imageIds.get(i);
                ProductFeedbackImage img = imageMapper.selectById(imageId);
                if (img == null || img.getFeedbackId() != null || !me.equals(img.getCreateUser())) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST, "图片无效或已被使用");
                }
                img.setFeedbackId(fb.getId());
                img.setSortOrder(i);
                imageMapper.updateById(img);
            }
        }
        return fb.getId();
    }

    /** 我的反馈历史：超管看本租户全部、成员看自己。 */
    public List<FeedbackListItem> listMine() {
        String owner = permissionResolver.ownerScopeOrNull();
        LambdaQueryWrapper<ProductFeedback> qw = new LambdaQueryWrapper<ProductFeedback>()
                .eq(ProductFeedback::getTenantId, TenantContext.get())
                .eq(owner != null, ProductFeedback::getCreateUser, owner)
                .orderByDesc(ProductFeedback::getCreateTime);
        List<ProductFeedback> rows = feedbackMapper.selectList(qw);
        List<FeedbackListItem> out = new ArrayList<>();
        for (ProductFeedback f : rows) {
            out.add(new FeedbackListItem(f.getId(), f.getTenantId(), null,
                    f.getFeedbackType(), f.getContent(), countImages(f.getId()), f.getCreateTime()));
        }
        return out;
    }

    /** 反馈详情（属主/超管校验）。 */
    public FeedbackDetail detailMine(Long id) {
        ProductFeedback f = feedbackMapper.selectById(id);
        if (f == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "反馈不存在");
        }
        permissionResolver.assertOwnerOrSuperAdmin(f.getCreateUser());
        return toDetail(f, null);
    }

    /** 流式回传图片字节：void+OutputStream 绕 GlobalResponseHandler。checkOwner=false 时跳过属主校验（运营端用）。 */
    public void streamImage(Long imageId, HttpServletResponse response, boolean checkOwner) throws Exception {
        ProductFeedbackImage img = imageMapper.selectById(imageId);
        if (img == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "图片不存在");
        }
        if (checkOwner) {
            permissionResolver.assertOwnerOrSuperAdmin(img.getCreateUser());
        }
        response.setContentType(img.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : img.getContentType());
        response.setHeader("Content-Disposition", "inline");
        try (InputStream is = storage.download(img.getObjectKey());
             OutputStream out = response.getOutputStream()) {
            is.transferTo(out);
            out.flush();
        }
    }

    /** 组装详情 DTO（含按 sort_order 排序的图片）。tenantName 由调用方传入（运营端用）。 */
    public FeedbackDetail toDetail(ProductFeedback f, String tenantName) {
        List<ProductFeedbackImage> imgs = imageMapper.selectList(
                new LambdaQueryWrapper<ProductFeedbackImage>().eq(ProductFeedbackImage::getFeedbackId, f.getId()));
        List<FeedbackImageView> views = imgs.stream()
                .sorted(Comparator.comparingInt(i -> i.getSortOrder() == null ? 0 : i.getSortOrder()))
                .map(i -> new FeedbackImageView(i.getId(), i.getContentType(), i.getSortOrder()))
                .toList();
        return new FeedbackDetail(f.getId(), f.getTenantId(), tenantName,
                f.getFeedbackType(), f.getContent(), f.getCreateTime(), views);
    }

    private int countImages(Long feedbackId) {
        return Math.toIntExact(imageMapper.selectCount(
                new LambdaQueryWrapper<ProductFeedbackImage>().eq(ProductFeedbackImage::getFeedbackId, feedbackId)));
    }
}
