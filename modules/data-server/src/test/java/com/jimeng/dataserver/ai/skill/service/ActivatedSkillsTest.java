package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActivatedSkillsTest {

    private static ToolPackage pkg(String name) {
        return new ToolPackage() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return ""; }
            @Override public String getBody() { return ""; }
            @Override public List<SkillToolDefinition> getTools() { return List.of(); }
        };
    }

    @Test
    @DisplayName("一轮之内记下激活的包；结束后清空；没开轮次时 add 是空操作")
    void lifecycle() {
        ActivatedSkills.add(List.of(pkg("outside")));
        assertThat(ActivatedSkills.current()).isEmpty();
        ActivatedSkills.begin();
        try {
            ActivatedSkills.add(List.of(pkg("a")));
            ActivatedSkills.add(List.of(pkg("b")));
            assertThat(ActivatedSkills.current()).extracting(ToolPackage::getName).containsExactly("a", "b");
        } finally {
            ActivatedSkills.end();
        }
        assertThat(ActivatedSkills.current()).isEmpty();
    }

    @Test
    @DisplayName("嵌套调用（例如工具里再调一次模型）各记各的，内层结束不会清掉外层")
    void nested() {
        ActivatedSkills.begin();
        try {
            ActivatedSkills.add(List.of(pkg("outer")));
            ActivatedSkills.begin();
            try {
                ActivatedSkills.add(List.of(pkg("inner")));
                assertThat(ActivatedSkills.current()).extracting(ToolPackage::getName).containsExactly("inner");
            } finally {
                ActivatedSkills.end();
            }
            assertThat(ActivatedSkills.current()).extracting(ToolPackage::getName).containsExactly("outer");
        } finally {
            ActivatedSkills.end();
        }
    }

    @Test
    void snapshotsAreImmutableAndThreadsDoNotShareAuthorization() throws Exception {
        ActivatedSkills.begin();
        try {
            ActivatedSkills.add(List.of(pkg("outer")));
            List<ToolPackage> snapshot = ActivatedSkills.current();
            ActivatedSkills.add(List.of(pkg("later")));
            assertThat(snapshot).extracting(ToolPackage::getName).containsExactly("outer");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.clear()).isInstanceOf(UnsupportedOperationException.class);
            java.util.concurrent.FutureTask<List<ToolPackage>> child = new java.util.concurrent.FutureTask<>(ActivatedSkills::current);
            Thread thread = new Thread(child);
            thread.start();
            thread.join(1000);
            assertThat(child.get(1, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
        } finally {
            ActivatedSkills.end();
        }
    }

}
