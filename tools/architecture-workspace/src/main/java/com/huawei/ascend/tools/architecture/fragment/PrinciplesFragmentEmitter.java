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

/**
 * W3 emitter: reads docs/governance/principle-coverage.yaml and emits one
 * {@code element ... "SAA Principle"} per principle plus per legacy principle,
 * plus an `operationalised_by` relationship from each principle to its
 * operationalising rules.
 * <p>
 * Rules themselves are emitted by {@link RulesFragmentEmitter}; this emitter
 * only emits the principle nodes and the principle->rule edges. The edges'
 * target identifiers (rule_<X>) MUST match the identifiers emitted by
 * RulesFragmentEmitter for the workspace to parse.
 */
public final class PrinciplesFragmentEmitter {

    private PrinciplesFragmentEmitter() {
    }

    public static void main(String[] args) throws IOException {
        Path repoRoot = Path.of(argValue(args, "--repo", "."));
        Path output = Path.of(argValue(args, "--output", "architecture/generated/principles.dsl"));

        Path coverage = repoRoot.resolve("docs/governance/principle-coverage.yaml");

        @SuppressWarnings("unchecked")
        Map<String, Object> root;
        try (var in = Files.newBufferedReader(coverage, StandardCharsets.UTF_8)) {
            root = (Map<String, Object>) new Yaml().load(in);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> principles = (List<Map<String, Object>>) root.getOrDefault("principles", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legacy = (List<Map<String, Object>>) root.getOrDefault("legacy_principles", List.of());

        int totalCount = principles.size() + legacy.size();
        try (FragmentWriter.StagedFragment frag = FragmentWriter.open(
                output,
                "docs/governance/principle-coverage.yaml",
                PrinciplesFragmentEmitter.class.getName(),
                totalCount)) {

            StringBuilder buf = frag.buf();
            emitPrinciples(buf, principles, false);
            emitPrinciples(buf, legacy, true);
        }

        System.out.println("PrinciplesFragmentEmitter wrote " + totalCount + " principles to " + output);
    }

    private static void emitPrinciples(StringBuilder buf,
                                       List<Map<String, Object>> principles,
                                       boolean legacy) {
        // Sort by id for determinism.
        principles.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        for (Map<String, Object> p : principles) {
            String id = String.valueOf(p.get("id"));
            String name = String.valueOf(p.getOrDefault("name", id));
            String identifier = "principle_" + FragmentWriter.safeId(id);
            String saaId = "PRINCIPLE-" + id;

            buf.append(identifier).append(" = element \"")
                    .append(FragmentWriter.escape(name))
                    .append("\" \"Principle\" \"")
                    .append(legacy ? "Legacy governing principle" : "L0 governing principle")
                    .append("\" \"SAA Principle\" {\n");

            Map<String, String> props = new LinkedHashMap<>();
            props.put("saa.id", saaId);
            props.put("saa.kind", legacy ? "legacy_principle" : "principle");
            props.put("saa.level", "L0");
            props.put("saa.view", "scenarios");
            props.put("saa.status", "shipped");
            props.put("saa.principleId", id);
            FragmentWriter.writeProperties(buf, props);
            buf.append("}\n\n");

            @SuppressWarnings("unchecked")
            List<String> rules = (List<String>) p.getOrDefault("operationalised_by_rules", List.of());
            if (rules != null) {
                // Dedupe by target rule identifier — sub-clause downgrade can produce
                // duplicate edges (e.g. Rule-G-3 and Rule-G-3.1 both downgrade to G-3).
                // Structurizr forbids two relationships between the same source/destination.
                java.util.TreeSet<String> sortedTargets = new java.util.TreeSet<>();
                for (String ruleName : rules) {
                    String stripped = ruleName.replaceFirst("^Rule[-_]", "");
                    int dotIdx = stripped.indexOf('.');
                    if (dotIdx >= 0) {
                        stripped = stripped.substring(0, dotIdx);
                    }
                    sortedTargets.add("rule_" + FragmentWriter.safeId(stripped));
                }
                for (String ruleIdent : sortedTargets) {
                    buf.append(identifier).append(" -> ").append(ruleIdent)
                            .append(" \"principle-coverage: operationalised_by_rules\" \"SAA Relationship\" {\n")
                            .append("    properties {\n")
                            .append("        \"saa.rel\" \"operationalised_by\"\n")
                            .append("    }\n")
                            .append("}\n");
                }
            }
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
