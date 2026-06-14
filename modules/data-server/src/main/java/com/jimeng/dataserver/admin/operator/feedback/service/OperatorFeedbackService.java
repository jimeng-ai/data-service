package com.jimeng.dataserver.admin.operator.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.feedback.dto.FeedbackDetail;
import com.jimeng.dataserver.ai.feedback.dto.FeedbackListItem;
import com.jimeng.dataserver.ai.feedback.service.FeedbackService;
import com.jimeng.persistence.entity.ProductFeedback;
import com.jimeng.persistence.entity.ProductFeedbackImage;
import com.jimeng.persistence.entity.SysEnterprise;
import com.jimeng.persistence.mapper.ProductFeedbackImageMapper;
import com.jimeng.persistence.mapper.ProductFeedbackMapper;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 运营门户：跨租户只读查看产品反馈。 */
@Service
@RequiredArgsConstructor
public class OperatorFeedbackService {

    private final ProductFeedbackMapper feedbackMapper;
    private final ProductFeedbackImageMapper imageMapper;
    private final SysEnterpriseMapper sysEnterpriseMapper;
    private final FeedbackService feedbackService;

    public Page<FeedbackListItem> page(int page, int size, Integer feedbackType,
                                       String tenantId, Date start, Date end) {
        return TenantContext.runAsSystem(() -> {
            LambdaQueryWrapper<ProductFeedback> qw = new LambdaQueryWrapper<ProductFeedback>()
                    .eq(feedbackType != null, ProductFeedback::getFeedbackType, feedbackType)
                    .eq(tenantId != null && !tenantId.isBlank(), ProductFeedback::getTenantId, tenantId)
                    .ge(start != null, ProductFeedback::getCreateTime, start)
                    .le(end != null, ProductFeedback::getCreateTime, end)
                    .orderByDesc(ProductFeedback::getCreateTime);
            Page<ProductFeedback> p = feedbackMapper.selectPage(new Page<>(page, size), qw);
            Map<String, String> names = loadTenantNames();
            List<FeedbackListItem> items = p.getRecords().stream()
                    .map(f -> new FeedbackListItem(f.getId(), f.getTenantId(),
                            names.get(f.getTenantId()), f.getFeedbackType(), f.getContent(),
                            countImages(f.getId()), f.getCreateTime()))
                    .toList();
            Page<FeedbackListItem> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
            out.setRecords(items);
            return out;
        });
    }

    public FeedbackDetail detail(Long id) {
        return TenantContext.runAsSystem(() -> {
            ProductFeedback f = feedbackMapper.selectById(id);
            if (f == null) {
                throw new ServiceException(ExceptionCode.NOT_FOUND, "反馈不存在");
            }
            return feedbackService.toDetail(f, loadTenantNames().get(f.getTenantId()));
        });
    }

    private int countImages(Long feedbackId) {
        return Math.toIntExact(imageMapper.selectCount(
                new LambdaQueryWrapper<ProductFeedbackImage>()
                        .eq(ProductFeedbackImage::getFeedbackId, feedbackId)));
    }

    private Map<String, String> loadTenantNames() {
        List<SysEnterprise> ents = sysEnterpriseMapper.selectList(null);
        Map<String, String> map = new HashMap<>();
        if (ents != null) {
            for (SysEnterprise e : ents) {
                if (e.getTenantId() != null) map.put(e.getTenantId(), e.getName());
            }
        }
        return map;
    }
}
