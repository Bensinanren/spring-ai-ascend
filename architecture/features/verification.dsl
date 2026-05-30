// architecture/features/verification.dsl
//
// Authority: ADR-0147 (Structurizr Workspace Authority).
// AUTHORED ZONE.
//
// SAA Test elements + `verifies` relationships covering the L1 function-point
// inventory at W2. Each entry names a Java test FQN (`saa.sourceFile`); the
// gate (W3+) cross-checks that the .java file exists.
//
// New tests land here as part of the same PR that adds the function point.
// W6 will not deprecate this file — it remains hand-authored after the YAML
// sunset.
//
// LAYER-PURITY DISCIPLINE (ADR-0159 §7). This is the structural TEST-CATALOGUE,
// and it sits ABOVE the L0/L1 prose in the authority cascade
// (generated facts > DSL > Card/prose). An element description here is a
// structural IDENTITY only: it names the test class, its harness flavour, and
// the function point it verifies (the `verifies` edge below carries the same
// structural intent). It MUST NOT carry asserted runtime behaviour — no route x
// verb (L4), no method-call chain (L1), no persistence mechanism (L3), no
// enumerated DFA transition outcome (L2/L8). The asserted behaviour of each
// test is owned by `architecture/facts/generated/tests.json` and surfaced
// through the verified FunctionPoint's `saa.test_refs[]` in
// `function-points.dsl`. Frame cards delegate the test inventory to those two
// fact surfaces for exactly this reason; this catalogue, being higher
// authority, holds itself to the same line.

testRunControllerCreateIT = element "RunControllerCreateIT" "IntegrationTest" "Integration test verifying FP-CREATE-RUN; asserted behaviour in tests.json + FP test_refs" "SAA Test" {
    properties {
        "saa.id" "TEST-RUNCONTROLLER-CREATE-IT"
        "saa.kind" "integration_test"
        "saa.level" "L1"
        "saa.view" "scenarios"
        "saa.status" "shipped"
        "saa.owner" "agent-service"
        "saa.sourceFile" "agent-service/src/test/java/com/huawei/ascend/service/platform/web/runs/RunHttpContractIT.java"
    }
}

testRunControllerCancelIT = element "RunControllerCancelIT" "IntegrationTest" "Integration test verifying FP-CANCEL-RUN; asserted behaviour in tests.json + FP test_refs" "SAA Test" {
    properties {
        "saa.id" "TEST-RUNCONTROLLER-CANCEL-IT"
        "saa.kind" "integration_test"
        "saa.level" "L1"
        "saa.view" "scenarios"
        "saa.status" "shipped"
        "saa.owner" "agent-service"
        "saa.sourceFile" "agent-service/src/test/java/com/huawei/ascend/service/platform/web/runs/RunHttpContractIT.java"
    }
}

testRunStateMachineTest = element "RunStateMachineTest" "UnitTest" "Unit test verifying FP-RUN-STATE-TRANSITION; asserted behaviour in tests.json + FP test_refs" "SAA Test" {
    properties {
        "saa.id" "TEST-RUNSTATEMACHINE"
        "saa.kind" "unit_test"
        "saa.level" "L1"
        "saa.view" "scenarios"
        "saa.status" "shipped"
        "saa.owner" "agent-service"
        "saa.sourceFile" "agent-service/src/test/java/com/huawei/ascend/service/runtime/runs/RunStateMachineTest.java"
    }
}

testIdempotencyStoreIT = element "IdempotencyStoreIT" "IntegrationTest" "Integration test verifying FP-IDEMPOTENCY-CLAIM; asserted behaviour in tests.json + FP test_refs" "SAA Test" {
    properties {
        "saa.id" "TEST-IDEMPOTENCYSTORE-IT"
        "saa.kind" "integration_test"
        "saa.level" "L1"
        "saa.view" "scenarios"
        "saa.status" "shipped"
        "saa.owner" "agent-service"
        "saa.sourceFile" "agent-service/src/test/java/com/huawei/ascend/service/platform/idempotency/IdempotencyStoreIT.java"
    }
}

testTenantContextFilterIT = element "TenantContextFilterIT" "IntegrationTest" "Integration test verifying FP-TENANT-CROSS-CHECK; asserted behaviour in tests.json + FP test_refs" "SAA Test" {
    properties {
        "saa.id" "TEST-TENANTCONTEXTFILTER-IT"
        "saa.kind" "integration_test"
        "saa.level" "L1"
        "saa.view" "scenarios"
        "saa.status" "shipped"
        "saa.owner" "agent-service"
        "saa.sourceFile" "agent-service/src/test/java/com/huawei/ascend/service/platform/tenant/TenantContextFilterIT.java"
    }
}

testEngineRegistryTest = element "EngineRegistryTest" "UnitTest" "Unit test verifying FP-ENGINE-DISPATCH; asserted behaviour in tests.json + FP test_refs" "SAA Test" {
    properties {
        "saa.id" "TEST-ENGINEREGISTRY"
        "saa.kind" "unit_test"
        "saa.level" "L1"
        "saa.view" "scenarios"
        "saa.status" "shipped"
        "saa.owner" "agent-execution-engine"
        "saa.sourceFile" "agent-service/src/test/java/com/huawei/ascend/engine/runtime/EngineRegistryResolveTest.java"
    }
}

testPostureBootGuardTest = element "PostureBootGuardTest" "UnitTest" "Unit test verifying FP-POSTURE-BOOT-GUARD; asserted behaviour in tests.json + FP test_refs" "SAA Test" {
    properties {
        "saa.id" "TEST-POSTUREBOOTGUARD"
        "saa.kind" "unit_test"
        "saa.level" "L1"
        "saa.view" "scenarios"
        "saa.status" "shipped"
        "saa.owner" "agent-service"
        "saa.sourceFile" "agent-service/src/test/java/com/huawei/ascend/service/platform/posture/PostureBootGuardIT.java"
    }
}

// Verification edges
testRunControllerCreateIT -> fpCreateRun "verifies create-Run handler" "SAA Relationship" {
    properties {

        "saa.rel" "verifies"

    }
}
testRunControllerCancelIT -> fpCancelRun "verifies cancel-Run handler" "SAA Relationship" {
    properties {

        "saa.rel" "verifies"

    }
}
testRunStateMachineTest -> fpRunStateTransition "verifies DFA transitions" "SAA Relationship" {
    properties {

        "saa.rel" "verifies"

    }
}
testIdempotencyStoreIT -> fpIdempotencyClaim "verifies idempotency claim + replay" "SAA Relationship" {
    properties {

        "saa.rel" "verifies"

    }
}
testTenantContextFilterIT -> fpTenantCrossCheck "verifies tenant cross-check" "SAA Relationship" {
    properties {

        "saa.rel" "verifies"

    }
}
testEngineRegistryTest -> fpEngineDispatch "verifies engine dispatch" "SAA Relationship" {
    properties {

        "saa.rel" "verifies"

    }
}
testPostureBootGuardTest -> fpPostureBootGuard "verifies posture boot guard" "SAA Relationship" {
    properties {

        "saa.rel" "verifies"

    }
}
