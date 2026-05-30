package com.huawei.ascend.tools.architecture;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.structurizr.Workspace;
import com.structurizr.model.CustomElement;
import com.structurizr.model.Element;
import com.structurizr.model.Model;
import com.structurizr.model.Relationship;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Emits a normalized JSON view of a parsed Structurizr workspace.
 * <p>
 * Determinism contract: sorted keys, sorted arrays, LF line endings, UTF-8,
 * trailing newline. The same workspace MUST produce byte-identical output
 * across runs and across platforms. This contract is the foundation for
 * Rule G-13 byte-identical regeneration of {@code architecture/generated/*.dsl}
 * in Wave 3 and the reverse projection to {@code architecture-graph.yaml}
 * in Wave 4.
 */
public final class NormalizedModelWriter {

    private final ObjectMapper mapper;

    public NormalizedModelWriter() {
        this.mapper = new ObjectMapper()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .enable(SerializationFeature.INDENT_OUTPUT);

        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n"));
        printer.indentObjectsWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n"));
        this.mapper.setDefaultPrettyPrinter(printer);
    }

    public Map<String, Object> normalize(Workspace workspace) {
        Model model = workspace.getModel();

        List<Map<String, Object>> elements = new ArrayList<>();
        for (Element el : model.getElements()) {
            elements.add(elementToMap(el));
        }
        elements.sort(Comparator.comparing(m -> (String) m.get("saa_id_or_name")));

        List<Map<String, Object>> relationships = new ArrayList<>();
        for (Relationship rel : model.getRelationships()) {
            relationships.add(relationshipToMap(rel));
        }
        relationships.sort(
                Comparator.<Map<String, Object>, String>comparing(m -> (String) m.get("source"))
                        .thenComparing(m -> (String) m.get("destination"))
                        .thenComparing(m -> (String) m.get("rel_type")));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "architecture-workspace/v1");
        root.put("workspace_name", workspace.getName());
        root.put("element_count", elements.size());
        root.put("relationship_count", relationships.size());
        root.put("elements", elements);
        root.put("relationships", relationships);
        return root;
    }

    public void write(Workspace workspace, Path output) throws IOException {
        Map<String, Object> normalized = normalize(workspace);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
        json = json.replace("\r\n", "\n");
        if (!json.endsWith("\n")) {
            json = json + "\n";
        }
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }

    private Map<String, Object> elementToMap(Element item) {
        Map<String, Object> m = new TreeMap<>();
        Map<String, String> props = item.getProperties();

        String identity = props.getOrDefault("saa.id", item.getId());
        String kind = (item instanceof CustomElement) ? "CustomElement" : item.getClass().getSimpleName();

        m.put("saa_id_or_name", identity);
        m.put("structurizr_id", item.getId());
        m.put("name", item.getName());
        m.put("structurizr_kind", kind);
        m.put("tags", sortedTags(item));
        m.put("properties", new TreeMap<>(props));
        return m;
    }

    private Map<String, Object> relationshipToMap(Relationship rel) {
        Map<String, Object> m = new TreeMap<>();
        Map<String, String> props = rel.getProperties();

        m.put("source", rel.getSource().getCanonicalName());
        m.put("destination", rel.getDestination().getCanonicalName());
        m.put("rel_type", props.getOrDefault("saa.rel", "<untyped>"));
        m.put("description", rel.getDescription() == null ? "" : rel.getDescription());
        m.put("tags", sortedTags(rel));
        m.put("properties", new TreeMap<>(props));
        return m;
    }

    private List<String> sortedTags(Element item) {
        return item.getTagsAsSet().stream().sorted().toList();
    }

    private List<String> sortedTags(Relationship rel) {
        return rel.getTagsAsSet().stream().sorted().toList();
    }
}
