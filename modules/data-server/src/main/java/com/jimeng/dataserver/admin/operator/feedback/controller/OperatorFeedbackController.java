package com.jimeng.dataserver.admin.operator.feedback.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.dataserver.admin.operator.common.OperatorGuard;
import com.jimeng.dataserver.admin.operator.feedback.service.OperatorFeedbackService;
import com.jimeng.dataserver.ai.feedback.dto.FeedbackDetail;
import com.jimeng.dataserver.ai.feedback.dto.FeedbackListItem;
import com.jimeng.dataserver.ai.feedback.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

/** 运营门户：跨租户只读查看产品反馈。先经 OperatorGuard 校验运营身份。 */
@Tag(name = "运营-产品反馈", description = "跨租户只读查看产品反馈")
@RestController
@RequestMapping("/data/admin/operator/feedbacks")
@RequiredArgsConstructor
public class OperatorFeedbackController {

    private final OperatorFeedbackService operatorFeedbackService;
    private final FeedbackService feedbackService;
    private final OperatorGuard operatorGuard;

    @Operation(summary = "跨租户反馈分页列表")
    @GetMapping
    public Page<FeedbackListItem> list(@RequestParam(name = "page", defaultValue = "1") int page,
                                       @RequestParam(name = "size", defaultValue = "20") int size,
                                       @RequestParam(name = "feedbackType", required = false) Integer feedbackType,
                                       @RequestParam(name = "tenantId", required = false) String tenantId,
                                       @RequestParam(name = "start", required = false) Long start,
                                       @RequestParam(name = "end", required = false) Long end) {
        operatorGuard.requireOperatorId();
        return operatorFeedbackService.page(page, size, feedbackType, tenantId,
                start == null ? null : new Date(start), end == null ? null : new Date(end));
    }

    @Operation(summary = "反馈详情")
    @GetMapping("/{id}")
    public FeedbackDetail detail(@PathVariable Long id) {
        operatorGuard.requireOperatorId();
        return operatorFeedbackService.detail(id);
    }

    @Operation(summary = "反馈图片流式预览")
    @GetMapping("/images/{imageId}")
    public void image(@PathVariable Long imageId, HttpServletResponse response) throws Exception {
        operatorGuard.requireOperatorId();
        feedbackService.streamImage(imageId, response, false); // 运营已鉴权，跳过属主校验
    }
}
