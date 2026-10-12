package com.jimeng.dataserver.ai.http.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内网判定与出网代理的 isBlockedIp 用同一份用例对拍（设计文档 §8.1 第 2 条最后一点）。
 * 用例文件在两个仓库各有一份，逐字节相同（Task 11 的 CrossRepoFixturesTest 在本机检查）。
 */
class BlockedAddressesParityTest {

    private static final Path CASES = Path.of("src/test/resources/contract/ip-block-cases.json");

    // data-server 的 Jackson 是 2.11.1（redisson 的 BOM 钉住的），不认 record：每个组件写明 JSON 名，紧凑构造器标 @JsonCreator
    record Case(@JsonProperty("ip") String ip, @JsonProperty("blocked") boolean blocked, @JsonProperty("note") String note) {
        @JsonCreator
        Case {}
    }

    static List<Case> cases() throws IOException {
        return new ObjectMapper().readValue(CASES.toFile(), new TypeReference<>() {});
    }

    @TestFactory
    @DisplayName("每条用例的判定与代理一致")
    Stream<DynamicTest> 与代理同一组用例() throws IOException {
        return cases().stream().map(c -> DynamicTest.dynamicTest(c.ip() + "（" + c.note() + "）", () ->
                assertThat(BlockedAddresses.isBlocked(InetAddress.getByName(c.ip()))).isEqualTo(c.blocked())));
    }

    @Test
    @DisplayName("用例文件读得到、两种结论都有（工作目录不对时读到空集会白白通过）")
    void 用例覆盖面() throws IOException {
        List<Case> cs = cases();
        assertThat(cs).hasSizeGreaterThanOrEqualTo(45);
        assertThat(cs).anyMatch(Case::blocked).anyMatch(c -> !c.blocked());
    }
}
