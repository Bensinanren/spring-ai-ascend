package com.huawei.ascend.tools.architecture.spike;

import com.structurizr.Workspace;
import com.structurizr.model.Element;
import com.structurizr.model.Model;
import com.structurizr.model.Relationship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wave 0 spike profile validator.
 * <p>
 * Empirical contract (structurizr-dsl 6.2.1): {@link Model#getElements()} returns
 * ALL elements INCLUDING CustomElements (CustomElement extends GroupableElement
 * extends Element). Walking both getElements() and getCustomElements() double-counts.
 * Validator MUST walk getElements() exactly once.
 */
public final class SpikeProfileValidator {

    private static final Set<String> PROFILE_TAGS = Set.of(
            "SAA Principle",
            "SAA Rule",
            "SAA Enforcer",
            "SAA Test",
            "SAA Module",
            "SAA Capability",
            "SAA Feature",
            "SAA FunctionPoint",
            "SAA SPI",
            "SAA Contract",
            "SAA ADR",
            "SAA GeneratedProjection"
    );

    private static final List<String> COMMON_PROPERTIES = List.of(
            "saa.id",
            "saa.kind",
            "saa.level",
            "saa.view",
            "saa.status"
    );

    private static final Map<String, List<String>> TAG_SPECIFIC = new HashMap<>();

    static {
        TAG_SPECIFIC.put("SAA Capability", List.of("saa.owner", "saa.sourceAdr"));
        TAG_SPECIFIC.put("SAA Feature", List.of("saa.owner", "saa.sourceAdr"));
        TAG_SPECIFIC.put("SAA FunctionPoint", List.of("saa.owner", "saa.sourceAdr"));
        TAG_SPECIFIC.put("SAA Module", List.of("saa.owner"));
        TAG_SPECIFIC.put("SAA Rule", List.of("saa.sourceAdr"));
        TAG_SPECIFIC.put("SAA Enforcer", List.of("saa.owner", "saa.sourceFile", "saa.enforcerId"));
        TAG_SPECIFIC.put("SAA Test", List.of("saa.owner", "saa.sourceFile"));
        TAG_SPECIFIC.put("SAA SPI", List.of("saa.owner", "saa.sourceFile"));
        TAG_SPECIFIC.put("SAA Contract", List.of("saa.owner", "saa.sourceFile", "saa.sourceAdr"));
        TAG_SPECIFIC.put("SAA Principle", List.of("saa.principleId"));
        TAG_SPECIFIC.put("SAA ADR", List.of("saa.adrId", "saa.adrStatus"));
        TAG_SPECIFIC.put("SAA GeneratedProjection", List.of("saa.generated", "saa.sourceFile"));
    }

    private static final Set<String> RELATIONSHIP_TYPES = Set.of(
            "contains",
            "implements",
            "verifies",
            "constrains",
            "depends_on",
            "declares_spi",
            "publishes_contract",
            "decides",
            "operationalised_by",
            "enforced_by",
            "supersedes",
            "extends",
            "relates_to",
            "indexes",
            "projects_to"
    );

    public List<ProfileViolation> validate(Workspace workspace) {
        List<ProfileViolation> violations = new ArrayList<>();
        Model model = workspace.getModel();

        for (Element el : model.getElements()) {
            validateModelItem(el, violations);
        }

        for (Relationship rel : model.getRelationships()) {
            validateRelationship(rel, violations);
        }

        return violations;
    }

    private void validateModelItem(Element item, List<ProfileViolation> violations) {
        Set<String> profileTagsOnItem = item.getTagsAsSet().stream()
                .filter(PROFILE_TAGS::contains)
                .collect(java.util.stream.Collectors.toSet());

        if (profileTagsOnItem.isEmpty()) {
            return;
        }

        String itemId = identityOf(item);
        Map<String, String> props = item.getProperties();

        for (String required : COMMON_PROPERTIES) {
            requireProp(itemId, props, required, violations);
        }

        for (String tag : profileTagsOnItem) {
            for (String required : TAG_SPECIFIC.getOrDefault(tag, List.of())) {
                requireProp(itemId, props, required, violations);
            }
        }
    }

    private void validateRelationship(Relationship rel, List<ProfileViolation> violations) {
        Map<String, String> props = rel.getProperties();
        String relType = props.get("saa.rel");
        if (relType == null || relType.isBlank()) {
            Set<String> tags = rel.getTagsAsSet();
            if (tags.contains("SAA Relationship")) {
                violations.add(new ProfileViolation(
                        identityOf(rel),
                        "SAA Relationship is missing required property saa.rel"));
            }
            return;
        }
        if (!RELATIONSHIP_TYPES.contains(relType)) {
            violations.add(new ProfileViolation(
                    identityOf(rel),
                    "illegal saa.rel value: " + relType));
        }
    }

    private static void requireProp(String itemId,
                                    Map<String, String> props,
                                    String key,
                                    List<ProfileViolation> violations) {
        String v = props.get(key);
        if (v == null || v.isBlank()) {
            violations.add(new ProfileViolation(itemId, "missing required property " + key));
        }
    }

    private static String identityOf(Element item) {
        Map<String, String> props = item.getProperties();
        String saaId = props.get("saa.id");
        if (saaId != null && !saaId.isBlank()) {
            return saaId;
        }
        return item.getCanonicalName();
    }

    private static String identityOf(Relationship rel) {
        Element src = rel.getSource();
        Element dst = rel.getDestination();
        return src.getCanonicalName() + " -> " + dst.getCanonicalName();
    }
}
