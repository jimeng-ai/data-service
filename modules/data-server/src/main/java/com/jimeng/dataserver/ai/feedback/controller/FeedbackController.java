package com.jimeng.dataserver.ai.feedback.controller;

import com.jimeng.dataserver.ai.feedback.dto.FeedbackDetail;
import com.jimeng.dataserver.ai.feedback.dto.FeedbackListItem;
import com.jimeng.dataserver.ai.feedback.dto.SubmitFeedbackRequest;
import com.jimeng.dataserver.ai.feedback.service.FeedbackService;
import com.jimeng.persistence.entity.ProductFeedbackImage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 租户端产品反馈：单图上传 + 提交 + 我的历史 + 详情 + 图片流式回传。 */
@Tag(name = "产品反馈", description = "租户用户提交产品反馈")
@RestController
@RequestMapping("/data/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "上传单张反馈图片", description = "存 MinIO 并返回 imageId，提交时引用。")
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadImage(@RequestParam("file") MultipartFile file) throws Exception {
        ProductFeedbackImage img = feedbackService.uploadImage(file);
        return Map.of("imageId", img.getId(), "contentType",
                img.getContentType() == null ? "" : img.getContentType());
    }

    @Operation(summary = "提交反馈")
    @PostMapping
    public Long submit(@RequestBody SubmitFeedbackRequest req) {
        return feedbackService.submit(req.getFeedbackType(), req.getContent(), req.getImageIds());
    }

    @Operation(summary = "我的反馈历史")
    @GetMapping
    public List<FeedbackListItem> listMine() {
        return feedbackService.listMine();
    }

    @Operation(summary = "反馈详情")
    @GetMapping("/{id}")
    public FeedbackDetail detail(@PathVariable Long id) {
        return feedbackService.detailMine(id);
    }

    @Operation(summary = "反馈图片流式预览")
    @GetMapping("/images/{imageId}")
    public void image(@PathVariable Long imageId, HttpServletResponse response) throws Exception {
        feedbackService.streamImage(imageId, response, true);
    }
}
