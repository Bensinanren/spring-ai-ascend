package com.huawei.ascend.tools.architecture.fragment;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Orchestrator: runs all 7 W3 fragment emitters in sequence with a single
 * repo root. Output paths are fixed under architecture/generated/.
 */
public final class AllFragmentsCli {

    private AllFragmentsCli() {
    }

    public static void main(String[] args) throws IOException {
        Path repoRoot = Path.of(argValue(args, "--repo", "."));
        String[] mkArgs = repoArg(repoRoot);

        System.out.println("--- ModulesFragmentEmitter");
        ModulesFragmentEmitter.main(merge(mkArgs, "--output", "architecture/generated/modules.dsl"));

        System.out.println("--- SpiCatalogFragmentEmitter");
        SpiCatalogFragmentEmitter.main(merge(mkArgs, "--output", "architecture/generated/spi-catalog.dsl"));

        System.out.println("--- EnforcersFragmentEmitter");
        EnforcersFragmentEmitter.main(merge(mkArgs, "--output", "architecture/generated/enforcers.dsl"));

        System.out.println("--- PrinciplesFragmentEmitter");
        PrinciplesFragmentEmitter.main(merge(mkArgs, "--output", "architecture/generated/principles.dsl"));

        System.out.println("--- RulesFragmentEmitter");
        RulesFragmentEmitter.main(merge(mkArgs, "--output", "architecture/generated/rules.dsl"));

        System.out.println("--- AdrGraphFragmentEmitter");
        AdrGraphFragmentEmitter.main(merge(mkArgs, "--output", "architecture/generated/adr-graph.dsl"));

        System.out.println("--- SurfaceClassificationFragmentEmitter");
        SurfaceClassificationFragmentEmitter.main(merge(mkArgs, "--output", "architecture/generated/surface-classification.dsl"));

        System.out.println("All 7 fragments emitted.");
    }

    private static String[] repoArg(Path repo) {
        return new String[]{"--repo", repo.toString()};
    }

    private static String[] merge(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
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
