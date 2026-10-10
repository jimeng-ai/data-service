package com.jimeng.dataserver.ai.skill.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService.ObjectInfo;
import com.jimeng.dataserver.ai.skill.LogCapture;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.ReportView;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.ReviewView;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评审页、触发优化报告、feedback.json 的读取（第 4 轮 1）：和草稿、发布用同一个有界读取（上限 reviewMaxBytes），
 * 读失败的文案不带存储异常的 message。真的 SkillWorkspaceReader + 真的 SkillBuilderSessionService，存储和 mapper 是桩。
 */
class SkillBuilderSessionServiceReviewTest {

    private static final String WS = "ws/1/";
    private static final String REVIEW = "skill/refund-flow-workspace/iteration-1/review.html";
    private static final String FEEDBACK = "skill/refund-flow-workspace/iteration-1/feedback.json";
    private static final String REPORT = "skill/refund-flow-workspace/description-optimization/report.html";

    private final RagMinioStorageService storage = mock(RagMinioStorageService.class);
    private final SkillBuilderSessionMapper sessionMapper = mock(SkillBuilderSessionMapper.class);
    private final SkillBuilderProperties props = new SkillBuilderProperties();
    private final SkillBuilderSessionService svc = new SkillBuilderSessionService(sessionMapper, mock(AiSkillMapper.class),
            mock(AgentMapper.class), mock(ChatConversationService.class), storage, new SkillWorkspaceReader(storage, props), props,
            new ObjectMapper(), mock(SkillSecretService.class));

    @BeforeEach
    void anOwnedSession() {
        props.setReviewMaxBytes(1024);
        SkillBuilderSession s = new SkillBuilderSession();
        s.setId(1L);
        s.setOwnerUserId(7L);
        s.setWorkspacePrefix(WS);
        s.setStatus(SkillBuilderSessionService.STATUS_ACTIVE);
        when(sessionMapper.selectById(1L)).thenReturn(s);
        AdminRequestContext.setAsyncUserId(7L);
    }

    @AfterEach
    void forgetTheUser() {
        AdminRequestContext.clearAsyncUserId();
    }

    /** 工作区里有 skill/refund-flow/SKILL.md 和这些文件；declared：列举时报的大小（可以和内容对不上）。 */
    private void workspace(Map<String, String> files, Map<String, Long> declared) throws Exception {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("skill/refund-flow/SKILL.md", "---\nname: refund-flow\ndescription: 退款\n---\n正文\n");
        all.putAll(files);
        List<ObjectInfo> infos = new ArrayList<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            infos.add(new ObjectInfo(WS + e.getKey(), declared.getOrDefault(e.getKey(), (long) bytes.length), new Date()));
            StorageStubs.object(storage, WS + e.getKey(), bytes);
        }
        when(storage.listObjectInfos(WS)).thenReturn(infos);
    }

    @Test
    @DisplayName("★ 评审页、feedback.json、触发优化报告：列举时报的大小在上限以内、读的时候已经被换大——按超限处理（tooLarge / 不给反馈），"
            + "读进内存的不超过上限 + 1 个字节（只调有界读取，没调整个读）")
    void reviewFilesThatGrewAfterTheListingAreCappedWhenTheyAreRead() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(REVIEW, "<html>" + "r".repeat(5000) + "</html>");
        files.put(FEEDBACK, "{\"reviews\":[{\"feedback\":\"" + "f".repeat(3000) + "\"}]}");
        files.put(REPORT, "<html>" + "o".repeat(5000) + "</html>");
        workspace(files, Map.of(REVIEW, 100L, FEEDBACK, 50L, REPORT, 100L));

        ReviewView review = svc.review(1L);
        ReportView report = svc.optimizationReport(1L);

        assertThat(review.isTooLarge()).isTrue();
        assertThat(review.getHtml()).isNull();
        assertThat(review.getFeedback()).isNull();
        assertThat(report.isTooLarge()).isTrue();
        assertThat(report.getHtml()).isNull();
        for (String rel : List.of(REVIEW, FEEDBACK, REPORT)) {
            verify(storage).readBytesUpTo(WS + rel, 1024L);
            verify(storage, never()).readBytes(WS + rel);
        }
    }

    @Test
    @DisplayName("上限以内的照常给：评审页 HTML、反馈（解析成对象）、触发优化报告；列举时就超了的连读都不读")
    void reviewFilesWithinTheCapAreReturned() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(REVIEW, "<html>评审</html>");
        files.put(FEEDBACK, "{\"reviews\":[]}");
        files.put(REPORT, "<html>报告</html>");
        workspace(files, Map.of());

        ReviewView review = svc.review(1L);
        assertThat(review.isTooLarge()).isFalse();
        assertThat(review.getHtml()).isEqualTo("<html>评审</html>");
        assertThat(review.getFeedback()).isEqualTo(Map.of("reviews", List.of()));
        assertThat(svc.optimizationReport(1L).getHtml()).isEqualTo("<html>报告</html>");

        workspace(files, Map.of(REVIEW, 1025L));
        org.mockito.Mockito.clearInvocations(storage);
        assertThat(svc.review(1L).isTooLarge()).isTrue();
        verify(storage, never()).readBytesUpTo(eq(WS + REVIEW), anyLong());
        verify(storage, never()).readBytes(WS + REVIEW);
    }

    @Test
    @DisplayName("★ 读评审页失败：文案里只有一句固定的话和异常类名——不带存储报的出错信息（resource=/…/review.html），"
            + "也不写路径（评审页不读 secrets.json，值集合没有确定，按 R22 不写路径；路径里唯一可变的是 skill 目录名）")
    void aFailedReviewReadCarriesNeitherThePathNorTheStorageMessage() throws Exception {
        workspace(new LinkedHashMap<>(Map.of(REVIEW, "<html>评审</html>")), Map.of());
        IOException gone = new IOException("The specified key does not exist. resource=/skills-bucket/" + WS + REVIEW);
        when(storage.readBytesUpTo(eq(WS + REVIEW), anyLong())).thenThrow(gone);
        when(storage.readBytes(WS + REVIEW)).thenThrow(gone);

        try (LogCapture logs = LogCapture.everything()) {
            assertThatThrownBy(() -> svc.review(1L)).isInstanceOf(ServiceException.class)
                    .hasMessageContaining("读取工作区文件失败").hasMessageContaining("IOException")
                    .hasMessageNotContaining("resource=").hasMessageNotContaining("does not exist").hasMessageNotContaining("refund-flow");
            assertThat(logs.all()).noneMatch(m -> m.contains("resource=") || m.contains("refund-flow"));
        }
    }

    @Test
    @DisplayName("feedback.json 解析失败：照旧跳过反馈，WARN 只记异常类名——不记 Jackson 的出错信息（它带着文件开头最多 500 个字节的原文）")
    void anUnparsableFeedbackIsLoggedWithoutItsContent() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(REVIEW, "<html>评审</html>");
        files.put(FEEDBACK, "{\"reviews\":[\"key 是 sk-live-CRM-0123456789\"");   // 写到一半
        workspace(files, Map.of());

        try (LogCapture logs = LogCapture.everything()) {
            ReviewView review = svc.review(1L);

            assertThat(review.getHtml()).isEqualTo("<html>评审</html>");
            assertThat(review.getFeedback()).isNull();
            assertThat(logs.warnings()).singleElement().satisfies(m -> assertThat(m).contains("feedback.json").doesNotContain("sk-live"));
            assertThat(logs.all()).noneMatch(m -> m.contains("sk-live"));
        }
        verify(storage, never()).readBytes(anyString());
    }
}
