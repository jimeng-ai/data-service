package com.jimeng.dataserver.ai.skill.builder;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.LogCapture;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteError;
import io.minio.messages.Item;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 构建器工作区的清理（{@link SkillBuilderJanitor}）：工作区里的文件名可能带着 key 的值，删不掉的对象、清理时的异常，
 * 都不能把对象名带进日志（日志经 Filebeat 进 ES）。真的 RagMinioStorageService，底下的 MinioClient 是桩。
 */
class SkillBuilderJanitorTest {

    private static final String FAKE = "sk-live-FAKE-0123456789";
    private static final String WS = "skill-builder/t1/5/ws/";
    private static final String STUCK = WS + "skill/refund-flow/scripts/" + FAKE + ".py";

    /** 查会话用的 LambdaQueryWrapper 创建时就要解析列名，要用 MP 的实体缓存；纯单测里没有 Spring，得自己灌一遍。 */
    @BeforeAll
    static void initEntityCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SkillBuilderSession.class);
    }

    private final MinioClient client = mock(MinioClient.class);
    private final RagMinioStorageService storage = storageOver(client);
    private final SkillBuilderSessionMapper sessionMapper = mock(SkillBuilderSessionMapper.class);
    private final AiSkillMapper aiSkillMapper = mock(AiSkillMapper.class);
    private final SkillBuilderJanitor janitor = new SkillBuilderJanitor(sessionMapper, aiSkillMapper, storage, new SkillBuilderProperties());

    private static RagMinioStorageService storageOver(MinioClient client) {
        RagMinioStorageService s = new RagMinioStorageService(null, null);
        ReflectionTestUtils.setField(s, "client", client);
        ReflectionTestUtils.setField(s, "bucket", "skills-bucket");
        return s;
    }

    private static Item item(String name) {
        Item it = mock(Item.class);
        when(it.objectName()).thenReturn(name);
        return it;
    }

    private static DeleteError cannotDelete(String name) {
        DeleteError err = mock(DeleteError.class);
        when(err.objectName()).thenReturn(name);
        when(err.message()).thenReturn("Access Denied: " + name);
        return err;
    }

    /** 一个要清理的会话（已发布 / 已放弃，或者闲置）；它的 DRAFT 行 id 是 77。 */
    private void aSessionToClean() {
        SkillBuilderSession s = new SkillBuilderSession();
        s.setId(5L);
        s.setWorkspacePrefix(WS);
        s.setDraftSkillId(77L);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s));
    }

    @Test
    @DisplayName("★ 第 4 轮（再续）R34：工作区里有对象删不掉（对象名里带着值）——只记删不掉几个，不记对象名，存储层也不记")
    void aWorkspaceObjectThatCannotBeDeletedIsCountedNotNamed() throws Exception {
        aSessionToClean();
        List<Result<Item>> listed = List.of(new Result<>(item(WS + "skill/refund-flow/SKILL.md")), new Result<>(item(STUCK)));
        List<Result<DeleteError>> failed = List.of(new Result<>(cannotDelete(STUCK)));   // 桩先建好：不能在另一个 when(...) 里面再打桩
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(listed);
        when(client.removeObjects(any(RemoveObjectsArgs.class))).thenReturn(failed);

        try (LogCapture logs = LogCapture.everything()) {
            janitor.sweepFinished();

            assertThat(logs.all()).noneMatch(m -> m.contains(FAKE));
            assertThat(logs.warnings()).anySatisfy(m -> assertThat(m).contains("1 个对象没删掉").contains("session=5"));
        }
    }

    @Test
    @DisplayName("★ 第 4 轮（再续）R34：清理时出异常（列工作区、清过期的临时前缀、放弃闲置会话），janitor 的 WARN 只记异常类名，不记 message")
    void janitorWarningsCarryOnlyTheExceptionClass() throws Exception {
        aSessionToClean();
        when(client.listObjects(any(ListObjectsArgs.class))).thenThrow(new IllegalStateException("listing failed near " + STUCK));
        when(aiSkillMapper.selectById(any())).thenThrow(new IllegalStateException("draft row failed near " + FAKE));

        try (LogCapture logs = LogCapture.everything()) {
            janitor.sweepFinished();                         // 删工作区：列举就失败
            janitor.sweepTempPrefix("skills/eval/", 3);      // 清过期的临时前缀：列举就失败
            janitor.sweepIdle();                             // 放弃闲置会话：查 DRAFT 行失败

            assertThat(logs.all()).noneMatch(m -> m.contains(FAKE));
            assertThat(logs.warnings()).hasSize(3).allSatisfy(m -> assertThat(m).contains("IllegalStateException"));
        }
    }

    @Test
    @DisplayName("RagMinioStorageService.deletePrefix 原有的日志不变：删不掉的对象照样记对象名（RAG 那边排障要用）")
    void deletePrefixKeepsNamingFailedObjectsForRag() throws Exception {
        String doc = "rag/2026/10/10/a.pdf";
        List<Result<Item>> listed = List.of(new Result<>(item(doc)));
        List<Result<DeleteError>> failed = List.of(new Result<>(cannotDelete(doc)));
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(listed);
        when(client.removeObjects(any(RemoveObjectsArgs.class))).thenReturn(failed);

        try (LogCapture logs = LogCapture.of(RagMinioStorageService.class)) {
            assertThat(storage.deletePrefix("rag/2026/10/10/")).isZero();
            assertThat(logs.warnings()).singleElement().satisfies(m -> assertThat(m).contains("object=" + doc));
        }
    }
}
