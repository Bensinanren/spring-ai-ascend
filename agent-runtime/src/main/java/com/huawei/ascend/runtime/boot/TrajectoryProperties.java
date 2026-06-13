package com.huawei.ascend.runtime.boot;

import com.huawei.ascend.runtime.engine.spi.TrajectoryMasking;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for northbound trajectory observability. {@code enabled} is the only
 * switch; a request may opt out via the {@code trajectory.level=off} A2A metadata key.
 */
@ConfigurationProperties(prefix = "app.trajectory")
public class TrajectoryProperties {

    private boolean enabled = true;
    private double sampleRate = 1.0;
    private final Mask mask = new Mask();
    private final Otel otel = new Otel();
    private final PayloadRef payloadRef = new PayloadRef();

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getSampleRate() { return sampleRate; }

    public void setSampleRate(double sampleRate) {
        this.sampleRate = Math.max(0.0, Math.min(1.0, sampleRate));
    }

    public Mask getMask() { return mask; }

    public Otel getOtel() { return otel; }

    public PayloadRef getPayloadRef() { return payloadRef; }

    public static class Mask {
        private String keyPattern = TrajectoryMasking.DEFAULT_KEY_PATTERN;
        private int truncateChars = 256;

        public String getKeyPattern() { return keyPattern; }

        public void setKeyPattern(String keyPattern) { this.keyPattern = keyPattern; }

        public int getTruncateChars() { return truncateChars; }

        public void setTruncateChars(int truncateChars) { this.truncateChars = truncateChars; }
    }

    /** Optional OpenTelemetry span export of the trajectory. Off by default. */
    public static class Otel {
        private boolean enabled = false;
        private String endpoint = "http://localhost:4317";

        public boolean isEnabled() { return enabled; }

        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getEndpoint() { return endpoint; }

        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    }

    /**
     * Opt-in out-of-band storage for over-threshold prompt/completion payloads. Off by
     * default ({@code enabled=false}); when enabled, a {@code LocalFsPayloadRefStore} is
     * wired using {@code baseDir}. Remote backends are a future {@code PayloadRefStore}
     * implementation; this config covers the local-filesystem variant only.
     */
    public static class PayloadRef {
        private boolean enabled = false;
        private String baseDir = "";
        /** Minimum string length (chars) before a slot is stored out-of-band. */
        private int threshold = 4096;
        /** Slot field paths eligible for ref-izing, e.g. {@code args}, {@code result}. */
        private List<String> fields = new ArrayList<>();

        public boolean isEnabled() { return enabled; }

        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getBaseDir() { return baseDir; }

        public void setBaseDir(String baseDir) { this.baseDir = baseDir; }

        public int getThreshold() { return threshold; }

        public void setThreshold(int threshold) { this.threshold = threshold; }

        public List<String> getFields() { return fields; }

        public void setFields(List<String> fields) { this.fields = fields; }
    }
}
