package com.huawei.ascend.agentsdk.spec.skill;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.agentsdk.support.ValidationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkillSourceLoaderTest {

    @Test
    void rejectsDuplicateSkillNamesAfterAggregatingAllSources() throws Exception {
        Path root = Files.createDirectories(Path.of("target", "skill-source-loader-test",
                UUID.randomUUID().toString()));
        Path first = Files.createDirectories(root.resolve("first").resolve("orders"));
        Path second = Files.createDirectories(root.resolve("second").resolve("orders"));
        Files.writeString(first.resolve("SKILL.md"), "# Orders A\n");
        Files.writeString(second.resolve("SKILL.md"), "# Orders B\n");

        assertThatThrownBy(() -> new SkillSourceLoader().load(List.of(
                new SkillSourceSpec("filesystem", first),
                new SkillSourceSpec("filesystem", second))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate skill name")
                .hasMessageContaining("orders");
    }
}
