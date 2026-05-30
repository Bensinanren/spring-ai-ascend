package com.huawei.ascend.tools.architecture.spike;

import com.structurizr.Workspace;
import com.structurizr.dsl.StructurizrDslParser;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Wave 0 spike CLI. Usage: {@code SpikeCli <workspace.dsl> [<output.json>]}.
 */
public final class SpikeCli {

    private SpikeCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: SpikeCli <workspace.dsl> [<output.json>]");
            System.exit(64);
        }

        File workspaceFile = new File(args[0]);
        if (!workspaceFile.isFile()) {
            System.err.println("ERROR: workspace file not found: " + workspaceFile);
            System.exit(1);
        }

        StructurizrDslParser parser = new StructurizrDslParser();
        try {
            parser.parse(workspaceFile);
        } catch (Exception e) {
            System.err.println("DSL PARSE FAILED: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
            return;
        }

        Workspace workspace = parser.getWorkspace();
        if (workspace == null) {
            System.err.println("ERROR: parser returned no workspace");
            System.exit(1);
            return;
        }

        System.out.println("WORKSPACE: " + workspace.getName());
        System.out.println("ELEMENTS: " + workspace.getModel().getElements().size());
        System.out.println("CUSTOM_ELEMENTS: " + workspace.getModel().getCustomElements().size());
        System.out.println("RELATIONSHIPS: " + workspace.getModel().getRelationships().size());

        List<ProfileViolation> violations = new SpikeProfileValidator().validate(workspace);
        if (!violations.isEmpty()) {
            System.err.println("PROFILE VIOLATIONS: " + violations.size());
            for (ProfileViolation v : violations) {
                System.err.println("  - " + v);
            }
            System.exit(2);
            return;
        }
        System.out.println("PROFILE: OK");

        if (args.length == 2) {
            Path out = Path.of(args[1]);
            new NormalizedModelWriter().write(workspace, out);
            System.out.println("NORMALIZED JSON written to " + out.toAbsolutePath());
        }
    }
}
