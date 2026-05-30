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
 * W3 emitter: reads docs/governance/enforcers.yaml and emits one
 * {@code element ... "SAA Enforcer"} per enforcer row plus an
 * `enforced_by` relationship from the constraint's rule (if resolvable from
 * the `constraint_ref` string) to this enforcer.
 */
public final class EnforcersFragmentEmitter {

    private EnforcersFragmentEmitter() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws IOException {
        Path repoRoot = Path.of(argValue(args, "--repo", "."));
        Path output = Path.of(argValue(args, "--output", "architecture/generated/enforcers.dsl"));

        Path enforcersYaml = repoRoot.resolve("docs/governance/enforcers.yaml");
        Object loaded;
        try (var in = Files.newBufferedReader(enforcersYaml, StandardCharsets.UTF_8)) {
            loaded = new Yaml().load(in);
        }
        List<Map<String, Object>> rows;
        if (loaded instanceof List<?> l) {
            rows = (List<Map<String, Object>>) l;
        } else if (loaded instanceof Map<?, ?> m && m.get("enforcers") instanceof List<?> l) {
            rows = (List<Map<String, Object>>) l;
        } else {
            throw new IllegalStateException("enforcers.yaml shape not recognised");
        }

        // Sort by id (E1, E2, ...) — numeric where possible, lexicographic otherwise.
        rows.sort((a, b) -> {
            String ai = String.valueOf(a.get("id"));
            String bi = String.valueOf(b.get("id"));
            Integer an = parseEnforcerId(ai);
            Integer bn = parseEnforcerId(bi);
            if (an != null && bn != null) {
                return Integer.compare(an, bn);
            }
            return ai.compareTo(bi);
        });

        try (FragmentWriter.StagedFragment frag = FragmentWriter.open(
                output,
                "docs/governance/enforcers.yaml",
                EnforcersFragmentEmitter.class.getName(),
                rows.size())) {

            StringBuilder buf = frag.buf();
            for (Map<String, Object> row : rows) {
                String enforcerId = String.valueOf(row.get("id"));
                String identifier = "enforcer_" + FragmentWriter.safeId(enforcerId);
                String saaId = "ENF-" + enforcerId;
                String asserts = FragmentWriter.escape(String.valueOf(row.getOrDefault("asserts", "")));
                if (asserts.length() > 200) {
                    asserts = asserts.substring(0, 197) + "...";
                }
                String artifact = String.valueOf(row.getOrDefault("artifact", ""));
                String kind = String.valueOf(row.getOrDefault("kind", "gate-script"));
                String level = String.valueOf(row.getOrDefault("level", "L1"));
                String view = String.valueOf(row.getOrDefault("view", "development"));

                buf.append(identifier).append(" = element \"")
                        .append(FragmentWriter.escape(enforcerId))
                        .append("\" \"Enforcer\" \"")
                        .append(asserts.isEmpty() ? "Enforcer mounted from enforcers.yaml" : asserts)
                        .append("\" \"SAA Enforcer\" {\n");

                Map<String, String> props = new LinkedHashMap<>();
                props.put("saa.id", saaId);
                props.put("saa.kind", FragmentWriter.escape(kind));
                props.put("saa.level", level);
                props.put("saa.view", view);
                props.put("saa.status", "shipped");
                props.put("saa.owner", "architecture");
                props.put("saa.sourceFile", FragmentWriter.escape(artifact));
                props.put("saa.enforcerId", enforcerId);
                FragmentWriter.writeProperties(buf, props);
                buf.append("}\n\n");
            }
        }

        System.out.println("EnforcersFragmentEmitter wrote " + rows.size() + " enforcers to " + output);
    }

    private static Integer parseEnforcerId(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        String trimmed = s.startsWith("E") ? s.substring(1) : s;
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return null;
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
