package com.huawei.ascend.tools.architecture.spike;

public record ProfileViolation(String itemId, String message) {
    @Override
    public String toString() {
        return itemId + ": " + message;
    }
}
