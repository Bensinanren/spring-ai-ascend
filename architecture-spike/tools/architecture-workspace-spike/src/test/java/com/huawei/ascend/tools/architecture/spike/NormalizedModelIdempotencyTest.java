package com.huawei.ascend.tools.architecture.spike;

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

class NormalizedModelIdempotencyTest {

    /**
     * Wave 0 gate G2: re-emit normalized JSON 3× consecutively; all three outputs
     * MUST be byte-identical (SHA-256 hashes equal).
     */
    @Test
    void threeConsecutiveEmissionsAreByteIdentical(@TempDir Path tempDir) throws Exception {
        StructurizrDslParser parser = new StructurizrDslParser();
        parser.parse(new File("src/test/resources/valid-spike-workspace.dsl"));
        Workspace workspace = parser.getWorkspace();

        NormalizedModelWriter writer = new NormalizedModelWriter();

        Path out1 = tempDir.resolve("emit1.json");
        Path out2 = tempDir.resolve("emit2.json");
        Path out3 = tempDir.resolve("emit3.json");

        writer.write(workspace, out1);
        writer.write(workspace, out2);
        writer.write(workspace, out3);

        String h1 = sha256(out1);
        String h2 = sha256(out2);
        String h3 = sha256(out3);

        assertEquals(h1, h2, "emit1 and emit2 must hash identical");
        assertEquals(h2, h3, "emit2 and emit3 must hash identical");
        assertEquals(Files.readString(out1), Files.readString(out2),
                "emit1 and emit2 must be byte-identical text");
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(Files.readAllBytes(p));
        return HexFormat.of().formatHex(hash);
    }
}
