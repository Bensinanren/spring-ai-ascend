package com.huawei.ascend.tools.architecture;

/**
 * One profile violation found in a parsed Structurizr workspace.
 * <p>
 * {@code itemId} is preferred to be the {@code saa.id} property; if absent,
 * canonical name. {@code severity} is reserved for W5+ when the gate may
 * downgrade some checks to warning.
 */
public record ProfileViolation(String itemId, String severity, String message) {
    public ProfileViolation(String itemId, String message) {
        this(itemId, "error", message);
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + itemId + ": " + message;
    }
}
