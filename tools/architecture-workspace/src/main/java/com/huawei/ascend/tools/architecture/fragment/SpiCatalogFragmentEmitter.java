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
import java.util.TreeMap;

/**
 * W3 emitter: scans every module-metadata.yaml#spi_packages and emits one
 * {@code element ... "SAA SPI"} per SPI package. Module->SPI declares_spi
 * relationship is also emitted.
 * <p>
 * The fragment targets identifier {@code spi_<safe_package_name>} to avoid
 * collision with the modules.dsl namespace.
 */
public final class SpiCatalogFragmentEmitter {

    private SpiCatalogFragmentEmitter() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws IOException {
        Path repoRoot = Path.of(argValue(args, "--repo", "."));
        Path output = Path.of(argValue(args, "--output", "architecture/generated/spi-catalog.dsl"));

        TreeMap<String, String> spiToModule = new TreeMap<>(); // sortedSpi -> moduleName
        try (var stream = Files.walk(repoRoot, 2)) {
            for (Path meta : stream.filter(p -> p.getFileName().toString().equals("module-metadata.yaml"))
                    .filter(p -> !p.toString().contains("/target/")).sorted().toList()) {
                try (var in = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                    Object loaded = new Yaml().load(in);
                    if (loaded instanceof Map<?, ?> map) {
                        String module = String.valueOf(map.get("module"));
                        Object packages = map.get("spi_packages");
                        if (packages instanceof List<?> l) {
                            for (Object o : l) {
                                if (o != null) {
                                    spiToModule.put(String.valueOf(o), module);
                                }
                            }
                        }
                    }
                }
            }
        }

        try (FragmentWriter.StagedFragment frag = FragmentWriter.open(
                output,
                "*/module-metadata.yaml#spi_packages",
                SpiCatalogFragmentEmitter.class.getName(),
                spiToModule.size())) {

            StringBuilder buf = frag.buf();
            for (Map.Entry<String, String> e : spiToModule.entrySet()) {
                String pkg = e.getKey();
                String module = e.getValue();
                String identifier = "spi_" + FragmentWriter.safeId(pkg);
                String saaId = "SPI-" + pkg.toUpperCase().replace(".", "_").replace("-", "_");

                buf.append(identifier).append(" = element \"")
                        .append(FragmentWriter.escape(pkg))
                        .append("\" \"SPI Package\" \"SPI package mounted from module-metadata.yaml\" \"SAA SPI\" {\n");

                Map<String, String> props = new LinkedHashMap<>();
                props.put("saa.id", saaId);
                props.put("saa.kind", "spi_package");
                props.put("saa.level", "L1");
                props.put("saa.view", "development");
                props.put("saa.status", "shipped");
                props.put("saa.owner", module);
                String sourcePath = module + "/src/main/java/" + pkg.replace(".", "/");
                props.put("saa.sourceFile", sourcePath);
                FragmentWriter.writeProperties(buf, props);
                buf.append("}\n\n");

                // Module -> SPI declares_spi (target the generated-zone module identifier).
                String moduleIdent = "genModule_" + FragmentWriter.safeId(module);
                buf.append(moduleIdent).append(" -> ").append(identifier)
                        .append(" \"module-metadata.yaml#spi_packages\" \"SAA Relationship\" {\n")
                        .append("    properties {\n")
                        .append("        \"saa.rel\" \"declares_spi\"\n")
                        .append("    }\n")
                        .append("}\n");
            }
        }

        System.out.println("SpiCatalogFragmentEmitter wrote " + spiToModule.size() + " SPI packages to " + output);
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
