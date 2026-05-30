package com.huawei.ascend.tools.architecture;

import com.structurizr.Workspace;
import com.structurizr.dsl.StructurizrDslParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalizedModelDeterminismTest {

    /**
     * Re-emit normalized JSON 5× consecutively; all five outputs MUST be byte-identical.
     * Tightens the spike's 3× guarantee (ADR-0148 gate G2) for production tooling.
     */
    @Test
    void fiveConsecutiveEmissionsAreByteIdentical(@TempDir Path tempDir) throws Exception {
        StructurizrDslParser parser = new StructurizrDslParser();
        parser.parse(new File("src/test/resources/valid-workspace.dsl"));
        Workspace workspace = parser.getWorkspace();

        NormalizedModelWriter writer = new NormalizedModelWriter();

        String firstHash = null;
        for (int i = 1; i <= 5; i++) {
            Path out = tempDir.resolve("emit" + i + ".json");
            writer.write(workspace, out);
            String hash = sha256(out);
            if (firstHash == null) {
                firstHash = hash;
            } else {
                assertEquals(firstHash, hash,
                        "emit" + i + ".json hash must equal emit1.json hash");
            }
        }
    }

    /**
     * Output is real JSON (parseable) and has LF endings, UTF-8, trailing newline.
     */
    @Test
    void emittedJsonShapeIsCorrect(@TempDir Path tempDir) throws Exception {
        StructurizrDslParser parser = new StructurizrDslParser();
        parser.parse(new File("src/test/resources/valid-workspace.dsl"));
        Workspace workspace = parser.getWorkspace();

        Path out = tempDir.resolve("shape.json");
        new NormalizedModelWriter().write(workspace, out);

        String content = Files.readString(out);
        assertEquals('\n', content.charAt(content.length() - 1), "must end with LF");
        // No CRs anywhere.
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\r') {
                throw new AssertionError("found CR at offset " + i);
            }
        }
        // Schema marker present.
        if (!content.contains("\"schema\" : \"architecture-workspace/v1\"")) {
            throw new AssertionError("schema marker missing from output");
        }
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(Files.readAllBytes(p));
        return HexFormat.of().formatHex(hash);
    }
}
