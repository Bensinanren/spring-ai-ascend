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
import java.util.TreeMap;

/**
 * W3 emitter: reads every docs/adr/*.yaml and emits one
 * {@code element ... "SAA ADR"} per ADR plus supersedes / extends /
 * relates_to relationships.
 */
public final class AdrGraphFragmentEmitter {

    private AdrGraphFragmentEmitter() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws IOException {
        Path repoRoot = Path.of(argValue(args, "--repo", "."));
        Path output = Path.of(argValue(args, "--output", "architecture/generated/adr-graph.dsl"));

        Path adrDir = repoRoot.resolve("docs/adr");
        TreeMap<String, Map<String, Object>> adrs = new TreeMap<>();
        try (var stream = Files.list(adrDir)) {
            for (Path p : stream.sorted().toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".yaml")) {
                    continue;
                }
                try (var in = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    Object loaded = new Yaml().load(in);
                    if (loaded instanceof Map<?, ?> map) {
                        Map<String, Object> typed = new LinkedHashMap<>();
                        for (var e : map.entrySet()) {
                            typed.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        String id = String.valueOf(typed.getOrDefault("id", ""));
                        if (!id.isEmpty()) {
                            adrs.put(id, typed);
                        }
                    }
                }
            }
        }

        try (FragmentWriter.StagedFragment frag = FragmentWriter.open(
                output,
                "docs/adr/*.yaml",
                AdrGraphFragmentEmitter.class.getName(),
                adrs.size())) {

            StringBuilder buf = frag.buf();

            for (Map.Entry<String, Map<String, Object>> e : adrs.entrySet()) {
                String id = e.getKey();
                Map<String, Object> data = e.getValue();
                String identifier = "adr_" + FragmentWriter.safeId(id);
                String saaId = id;
                String title = FragmentWriter.escape(String.valueOf(data.getOrDefault("title", id)));
                if (title.length() > 200) {
                    title = title.substring(0, 197) + "...";
                }
                String status = String.valueOf(data.getOrDefault("status", "draft"));
                String level = String.valueOf(data.getOrDefault("level", "L1"));
                String view = String.valueOf(data.getOrDefault("view", "scenarios"));

                buf.append(identifier).append(" = element \"")
                        .append(FragmentWriter.escape(id))
                        .append("\" \"ADR\" \"")
                        .append(title.isEmpty() ? id : title)
                        .append("\" \"SAA ADR\" {\n");

                Map<String, String> props = new LinkedHashMap<>();
                props.put("saa.id", saaId);
                props.put("saa.kind", "adr");
                props.put("saa.level", level);
                props.put("saa.view", view);
                props.put("saa.status", "shipped");
                props.put("saa.adrId", id);
                props.put("saa.adrStatus", status);
                FragmentWriter.writeProperties(buf, props);
                buf.append("}\n\n");
            }

            // Emit ADR -> ADR edges (supersedes / extends / relates_to).
            for (Map.Entry<String, Map<String, Object>> e : adrs.entrySet()) {
                String srcId = e.getKey();
                String srcIdent = "adr_" + FragmentWriter.safeId(srcId);
                emitEdges(buf, srcIdent, e.getValue(), adrs, "supersedes");
                emitEdges(buf, srcIdent, e.getValue(), adrs, "extends");
                emitEdges(buf, srcIdent, e.getValue(), adrs, "relates_to");
            }
        }

        System.out.println("AdrGraphFragmentEmitter wrote " + adrs.size() + " ADRs to " + output);
    }

    @SuppressWarnings("unchecked")
    private static void emitEdges(StringBuilder buf,
                                  String srcIdent,
                                  Map<String, Object> data,
                                  TreeMap<String, Map<String, Object>> adrs,
                                  String relType) {
        Object value = data.get(relType);
        if (value == null) {
            return;
        }
        List<String> targets = new ArrayList<>();
        if (value instanceof List<?> l) {
            for (Object o : l) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                // The yaml allows `ADR-0050    # comment`; trim to the ADR id.
                int hashIdx = s.indexOf('#');
                if (hashIdx >= 0) {
                    s = s.substring(0, hashIdx).trim();
                }
                if (!s.isEmpty()) {
                    targets.add(s);
                }
            }
        } else if (value instanceof String s) {
            targets.add(s.trim());
        }
        java.util.Collections.sort(targets);
        for (String t : targets) {
            if (!adrs.containsKey(t)) {
                continue;
            }
            String dstIdent = "adr_" + FragmentWriter.safeId(t);
            buf.append(srcIdent).append(" -> ").append(dstIdent)
                    .append(" \"adr-yaml: ").append(relType).append("\" \"SAA Relationship\" {\n")
                    .append("    properties {\n")
                    .append("        \"saa.rel\" \"").append(relType).append("\"\n")
                    .append("    }\n")
                    .append("}\n");
        }
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
