package com.huawei.ascend.runtime.engine.spi;

public enum RiskTier {
    R0_PURE_REASONING,
    R1_LOCAL_READ,
    R2_NETWORK_READ,
    R3_STATE_WRITE,
    R4_CODE_OR_SYSTEM_EXEC,
    R5_BUSINESS_CRITICAL,
    UNKNOWN
}
