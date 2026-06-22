package com.huawei.ascend.agentsdk.spec.skill;

import com.huawei.ascend.agentsdk.support.ValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class SkillSourceLoader {

    public List<SkillSpec> load(List<SkillSourceSpec> sources) {
        List<SkillSpec> skills = new ArrayList<>();
        for (SkillSourceSpec source : sources == null ? List.<SkillSourceSpec>of() : sources) {
            if (!"filesystem".equalsIgnoreCase(source.type())) {
                throw new ValidationException("Unsupported skill source type: " + source.type());
            }
            skills.addAll(loadFilesystem(source.path()));
        }
        rejectDuplicateNames(skills);
        return List.copyOf(skills);
    }

    private static void rejectDuplicateNames(List<SkillSpec> skills) {
        Map<String, Path> firstPaths = new LinkedHashMap<>();
        for (SkillSpec skill : skills) {
            Path previous = firstPaths.putIfAbsent(skill.name(), skill.path());
            if (previous != null) {
                throw new ValidationException("Duplicate skill name: " + skill.name()
                        + " (" + previous + ", " + skill.path() + ")");
            }
        }
    }

    private List<SkillSpec> loadFilesystem(Path source) {
        Path root = source.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ValidationException("Skill source directory does not exist: " + root);
        }
        Path direct = root.resolve("SKILL.md");
        List<Path> childSkills = childSkillDirs(root);
        if (Files.isRegularFile(direct)) {
            if (!childSkills.isEmpty()) {
                throw new ValidationException("Skill source has both direct SKILL.md and child skill directories: "
                        + root);
            }
            return List.of(new SkillSpec(root.getFileName().toString(), root, direct));
        }
        return childSkills.stream()
                .map(path -> new SkillSpec(path.getFileName().toString(), path, path.resolve("SKILL.md")))
                .toList();
    }

    private List<Path> childSkillDirs(Path root) {
        try (Stream<Path> children = Files.list(root)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("SKILL.md")))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException error) {
            throw new ValidationException("Failed to scan skill source: " + root, error);
        }
    }
}
