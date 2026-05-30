package com.huawei.ascend.tools.architecture.spike;

import com.structurizr.Workspace;
import com.structurizr.dsl.StructurizrDslParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SpikeProfileValidatorTest {

    @Test
    void validWorkspacePassesProfile() throws Exception {
        Workspace ws = parse("src/test/resources/valid-spike-workspace.dsl");
        List<ProfileViolation> violations = new SpikeProfileValidator().validate(ws);
        if (!violations.isEmpty()) {
            fail("Expected zero violations, got: " + violations);
        }
    }

    @Test
    void invalidCapabilityMissingOwnerFlagged() throws Exception {
        Workspace ws = parse("src/test/resources/invalid-missing-owner.dsl");
        List<ProfileViolation> violations = new SpikeProfileValidator().validate(ws);
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("saa.owner")),
                "expected violation citing saa.owner, got: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("saa.sourceAdr")),
                "expected violation citing saa.sourceAdr, got: " + violations);
        assertEquals(2, violations.size(),
                "expected exactly two violations (no double-counting), got: " + violations);
    }

    @Test
    void customElementsAreReachableByValidator() throws Exception {
        // Empirical contract (structurizr-dsl 6.2.1): Model.getElements() INCLUDES
        // CustomElements because CustomElement extends GroupableElement extends Element.
        // Walking getElements() alone reaches every profile-tagged item.
        Workspace ws = parse("src/test/resources/invalid-missing-owner.dsl");
        List<ProfileViolation> violations = new SpikeProfileValidator().validate(ws);
        assertTrue(violations.size() > 0,
                "validator must reach CustomElements — got 0 violations on broken fixture");
        assertEquals(1, ws.getModel().getCustomElements().size(),
                "fixture must contain exactly one custom element");
        assertEquals(1, ws.getModel().getElements().size(),
                "Model.getElements() must contain the CustomElement (extends Element)");
    }

    private Workspace parse(String relativePath) throws Exception {
        StructurizrDslParser parser = new StructurizrDslParser();
        parser.parse(new File(relativePath));
        return parser.getWorkspace();
    }
}
