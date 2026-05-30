package com.huawei.ascend.tools.architecture.fragment;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * W3 emitter: reads every {@code <module>/module-metadata.yaml}, emits one
 * {@code element ... "SAA Module"} per Maven module and depends_on
 * relationships from allowed_dependencies.
 * <p>
 * Reads the existing canonical module declarations from
 * architecture/workspace.dsl's container set — but those are CONTAINERS,
 * not custom elements. To avoid double-declaration, this emitter targets
 * a separate identifier namespace prefixed `genModule_`.
 */
public final class ModulesFragmentEmitter {

    private ModulesFragmentEmitter() {
    }

    public static void main(String[] args) throws IOException {
        Path repoRoot = Path.of(argValue(args, "--repo", "."));
        Path output = Path.of(argValue(args, "--output", "architecture/generated/modules.dsl"));

        Map<String, Map<String, Object>> modules = loadModules(repoRoot);

        try (FragmentWriter.StagedFragment frag = FragmentWriter.open(
                output,
                "*/module-metadata.yaml",
                ModulesFragmentEmitter.class.getName(),
                modules.size())) {

            StringBuilder buf = frag.buf();

            // Sort by module name for deterministic output.
            TreeMap<String, Map<String, Object>> sorted = new TreeMap<>(modules);

            for (Map.Entry<String, Map<String, Object>> e : sorted.entrySet()) {
                String moduleName = e.getKey();
                Map<String, Object> data = e.getValue();
                String identifier = "genModule_" + FragmentWriter.safeId(moduleName);
                String saaId = "GEN-MOD-" + moduleName.toUpperCase().replace("-", "_");
                String description = FragmentWriter.escape(
                        String.valueOf(data.getOrDefault("description", "")));
                if (description.length() > 200) {
                    description = description.substring(0, 197) + "...";
                }

                String kind = String.valueOf(data.getOrDefault("kind", "domain"));
                String plane = String.valueOf(data.getOrDefault("deployment_plane", "none"));

                buf.append(identifier).append(" = element \"")
                        .append(FragmentWriter.escape(moduleName))
                        .append("\" \"Module\" \"")
                        .append(description.isEmpty() ? "Maven module mounted from module-metadata.yaml" : description)
                        .append("\" \"SAA Module\" {\n");
                Map<String, String> props = new LinkedHashMap<>();
                props.put("saa.id", saaId);
                props.put("saa.kind", "module_metadata");
                props.put("saa.level", "L1");
                props.put("saa.view", "development");
                props.put("saa.status", "shipped");
                props.put("saa.owner", moduleName);
                props.put("saa.modulePath", moduleName);
                props.put("saa.moduleKind", kind);
                props.put("saa.deploymentPlane", plane);
                FragmentWriter.writeProperties(buf, props);
                buf.append("}\n\n");
            }

            // Emit depends_on relationships from allowed_dependencies.
            for (Map.Entry<String, Map<String, Object>> e : sorted.entrySet()) {
                String src = e.getKey();
                String srcId = "genModule_" + FragmentWriter.safeId(src);
                @SuppressWarnings("unchecked")
                List<String> allowed = (List<String>) e.getValue().getOrDefault("allowed_dependencies", List.of());
                if (allowed == null || allowed.isEmpty()) {
                    continue;
                }
                List<String> sortedAllowed = new ArrayList<>(allowed);
                java.util.Collections.sort(sortedAllowed);
                for (String dst : sortedAllowed) {
                    if (!sorted.containsKey(dst)) {
                        continue;
                    }
                    String dstId = "genModule_" + FragmentWriter.safeId(dst);
                    buf.append(srcId).append(" -> ").append(dstId)
                            .append(" \"module-metadata.yaml allowed dependency\" \"SAA Relationship\" {\n")
                            .append("    properties {\n")
                            .append("        \"saa.rel\" \"depends_on\"\n")
                            .append("    }\n")
                            .append("}\n");
                }
            }
        }

        System.out.println("ModulesFragmentEmitter wrote " + modules.size() + " module elements to " + output);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> loadModules(Path repoRoot) throws IOException {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        try (var stream = Files.walk(repoRoot, 2)) {
            List<Path> metas = stream
                    .filter(p -> p.getFileName().toString().equals("module-metadata.yaml"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .sorted()
                    .toList();
            for (Path meta : metas) {
                try (var in = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                    Object loaded = new Yaml().load(in);
                    if (loaded instanceof Map<?, ?> map) {
                        Map<String, Object> typed = new LinkedHashMap<>();
                        for (var entry : map.entrySet()) {
                            typed.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        String name = String.valueOf(typed.getOrDefault("module",
                                meta.getParent().getFileName().toString()));
                        result.put(name, typed);
                    }
                }
            }
        }
        return result;
    }

    private static String argValue(String[] args, String key, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }
}
