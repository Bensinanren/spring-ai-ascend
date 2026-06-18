package com.huawei.ascend.runtime.engine.spi;

public enum DecisionType {
    ALLOW,
    ALLOW_WITH_OBLIGATIONS,
    DENY,
    ASK_USER,
    SUSPEND_FOR_APPROVAL,
    ROUTE_TO_SANDBOX,
    REDACT_AND_RETRY,
    DEGRADE_TO_READ_ONLY
}
