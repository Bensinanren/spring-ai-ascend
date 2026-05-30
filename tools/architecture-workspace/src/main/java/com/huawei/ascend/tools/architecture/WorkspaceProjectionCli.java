package com.huawei.ascend.tools.architecture;

import com.structurizr.Workspace;
import com.structurizr.dsl.StructurizrDslParser;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Production CLI for the architecture workspace tool.
 * <p>
 * Usage:
 * <pre>
 *   WorkspaceProjectionCli parse     &lt;workspace.dsl&gt;
 *   WorkspaceProjectionCli validate  &lt;workspace.dsl&gt;
 *   WorkspaceProjectionCli normalize &lt;workspace.dsl&gt; &lt;output.json&gt;
 *   WorkspaceProjectionCli project   &lt;workspace.dsl&gt; &lt;output.yaml&gt;
 * </pre>
 * <p>
 * Exit codes:
 * <ul>
 *   <li>0 — success
 *   <li>1 — DSL parse failure or IO error
 *   <li>2 — profile violations
 *   <li>64 — bad usage
 * </ul>
 */
public final class WorkspaceProjectionCli {

    private WorkspaceProjectionCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(64);
        }
        String command = args[0];
        String workspacePath = args[1];

        File workspaceFile = new File(workspacePath);
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

        switch (command) {
            case "parse" -> doParse(workspace);
            case "validate" -> doValidate(workspace);
            case "normalize" -> {
                if (args.length != 3) {
                    System.err.println("normalize requires <output.json>");
                    System.exit(64);
                }
                doNormalize(workspace, Path.of(args[2]));
            }
            case "project" -> {
                if (args.length != 3) {
                    System.err.println("project requires <output.yaml>");
                    System.exit(64);
                }
                doProject(workspace, Path.of(args[2]));
            }
            default -> {
                System.err.println("unknown command: " + command);
                usage();
                System.exit(64);
            }
        }
    }

    private static void doParse(Workspace workspace) {
        System.out.println("WORKSPACE: " + workspace.getName());
        System.out.println("ELEMENTS: " + workspace.getModel().getElements().size());
        System.out.println("CUSTOM_ELEMENTS: " + workspace.getModel().getCustomElements().size());
        System.out.println("RELATIONSHIPS: " + workspace.getModel().getRelationships().size());
    }

    private static void doValidate(Workspace workspace) {
        doParse(workspace);
        List<ProfileViolation> violations = new ProfileValidator().validate(workspace);
        if (!violations.isEmpty()) {
            System.err.println("PROFILE VIOLATIONS: " + violations.size());
            for (ProfileViolation v : violations) {
                System.err.println("  - " + v);
            }
            System.exit(2);
            return;
        }
        System.out.println("PROFILE: OK");
    }

    private static void doNormalize(Workspace workspace, Path output) throws Exception {
        doValidate(workspace);
        new NormalizedModelWriter().write(workspace, output);
        System.out.println("NORMALIZED JSON written to " + output.toAbsolutePath());
    }

    private static void doProject(Workspace workspace, Path output) throws Exception {
        doValidate(workspace);
        new GraphProjectionWriter().write(workspace, output);
        System.out.println("GRAPH PROJECTION written to " + output.toAbsolutePath());
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  WorkspaceProjectionCli parse     <workspace.dsl>");
        System.err.println("  WorkspaceProjectionCli validate  <workspace.dsl>");
        System.err.println("  WorkspaceProjectionCli normalize <workspace.dsl> <output.json>");
        System.err.println("  WorkspaceProjectionCli project   <workspace.dsl> <output.yaml>");
    }
}
