package com.huawei.ascend.examples.deepresearch.read.a2a;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Copies a classpath resource (the assembly YAML lives inside the
 * agent-read jar at {@code /agent/agent.{prod,stub}.yaml}) to a temp file
 * and returns its {@link Path}. {@link com.huawei.ascend.agentsdk.factory.AgentFactory}
 * only accepts file paths, so we extract once at boot and hand the path to it.
 *
 * <p>Accepts both {@code classpath:/agent/agent.stub.yaml} and the bare
 * {@code /agent/agent.stub.yaml} form (the latter is what agent-search-a2a
 * uses); {@link #normalize} strips the {@code classpath:} prefix and ensures a
 * leading slash so {@link Class#getResourceAsStream(String)} resolves whether
 * or not the caller included it.
 */
final class ClasspathYamlExtractor {

    private ClasspathYamlExtractor() {
    }

    static Path extract(String classpathResource) {
        String normalized = normalize(classpathResource);
        try (InputStream in = ClasspathYamlExtractor.class.getResourceAsStream(normalized)) {
            if (in == null) {
                throw new IllegalStateException("yaml not on classpath: " + normalized);
            }
            Path tmpDir = Files.createTempDirectory("read-agent-yaml-");
            tmpDir.toFile().deleteOnExit();
            Path target = tmpDir.resolve(fileNameOf(normalized));
            target.toFile().deleteOnExit();
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("failed to extract yaml " + normalized, ex);
        }
    }

    private static String normalize(String classpathResource) {
        if (classpathResource == null || classpathResource.isBlank()) {
            return classpathResource;
        }
        if (classpathResource.startsWith("classpath:")) {
            String stripped = classpathResource.substring("classpath:".length());
            return stripped.startsWith("/") ? stripped : "/" + stripped;
        }
        return classpathResource.startsWith("/") ? classpathResource : "/" + classpathResource;
    }

    private static String fileNameOf(String classpathResource) {
        int slash = classpathResource.lastIndexOf('/');
        return slash < 0 ? classpathResource : classpathResource.substring(slash + 1);
    }
}
