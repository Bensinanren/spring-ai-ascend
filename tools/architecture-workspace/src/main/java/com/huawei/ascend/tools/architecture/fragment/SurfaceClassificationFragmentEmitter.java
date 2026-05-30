package com.huawei.ascend.tools.architecture.fragment;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * W3 emitter: reads docs/governance/templates/surface-classification.yaml and
 * emits one {@code element ... "SAA GeneratedProjection"} per registered
 * templated/hybrid surface.
 */
public final class SurfaceClassificationFragmentEmitter {

    private SurfaceClassificationFragmentEmitter() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws IOException {
        Path repoRoot = Path.of(argValue(args, "--repo", "."));
        Path output = Path.of(argValue(args, "--output", "architecture/generated/surface-classification.dsl"));

        Path registry = repoRoot.resolve("docs/governance/templates/surface-classification.yaml");
        Map<String, Object> root;
        try (var in = Files.newBufferedReader(registry, StandardCharsets.UTF_8)) {
            root = (Map<String, Object>) new Yaml().load(in);
        }
        List<Map<String, Object>> templates =
                (List<Map<String, Object>>) root.getOrDefault("templates", List.of());

        // Sort by output path for determinism.
        templates.sort((a, b) -> String.valueOf(a.get("output")).compareTo(String.valueOf(b.get("output"))));

        try (FragmentWriter.StagedFragment frag = FragmentWriter.open(
                output,
                "docs/governance/templates/surface-classification.yaml",
                SurfaceClassificationFragmentEmitter.class.getName(),
                templates.size())) {

            StringBuilder buf = frag.buf();
            for (Map<String, Object> t : templates) {
                String outPath = String.valueOf(t.getOrDefault("output", ""));
                String tmplPath = String.valueOf(t.getOrDefault("template", ""));
                String bucket = String.valueOf(t.getOrDefault("bucket", "templated"));
                String identifier = "surface_" + FragmentWriter.safeId(outPath);
                String saaId = "SURFACE-" + outPath.toUpperCase().replace("/", "_").replace(".", "_");

                buf.append(identifier).append(" = element \"")
                        .append(FragmentWriter.escape(outPath))
                        .append("\" \"GeneratedProjection\" \"")
                        .append(FragmentWriter.escape("Rendered from " + tmplPath + " (" + bucket + ")"))
                        .append("\" \"SAA GeneratedProjection\" {\n");

                Map<String, String> props = new LinkedHashMap<>();
                props.put("saa.id", saaId);
                props.put("saa.kind", "generated_projection");
                props.put("saa.level", "L0");
                props.put("saa.view", "scenarios");
                props.put("saa.status", "shipped");
                props.put("saa.generated", "true");
                props.put("saa.sourceFile", tmplPath);
                FragmentWriter.writeProperties(buf, props);
                buf.append("}\n\n");
            }
        }

        System.out.println("SurfaceClassificationFragmentEmitter wrote " + templates.size() + " surfaces to " + output);
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
