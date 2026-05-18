---
affects_level: L0
affects_view: process
proposal_status: external_review
authors: ["External architecture reviewer"]
related_rules: [Rule-1, Rule-4, Rule-9, Rule-32, Rule-79]
---

# Beyond SDD: Engineering the Cognitive Breakout
## Architecture Remediation Proposal for spring-ai-ascend

### 1. Current State: The SDD Moat and the "Narrative Shield" Trap
The `spring-ai-ascend` repository has successfully established top-tier **Spec-Driven Development (SDD)** infrastructure: 78 Gate scripts, zero-mock Integration Tests (IT), and an unyielding architectural anti-corruption layer. In the era of AI-generated code, this acts as a perfect moat against architectural decay.

However, over-reliance on static contracts (Docs-as-Code) and heavyweight integration tests introduces a fatal limitation: **When Large Language Models (AI coding agents) fall into logic loops or race-condition bugs, SDD acts as a "Narrative Shield".** The AI will stubbornly cite architectural contracts to defend its broken code, while the heavy Spring IT tests make "sub-second refactoring and breakout" impossible.

### 2. Remediation: The Return of Physical Reality and Micro-Driven Verification

To restore intrinsic agility while maintaining SDD's defensive power, we propose three core systemic changes:

#### 2.1 Telemetry-First Debugging (Raw Evidence Anchoring)
*   **Diagnostic Downgrade Principle:** When IT fails or bugs occur, AI agents must be **strictly prohibited** from reading architectural specs (ARCHITECTURE.md) for logical deduction as a first step.
*   **Physical Slice Anchoring:** Force the retrieval of raw physical evidence (Heap Dumps, dirty Kafka payloads, Trace logs). Use Raw Evidence to shatter the AI's logical hallucinations. Reality must dictate the spec, not the other way around.

#### 2.2 Library-Mode TDD as a Cognitive Breakout Engine
*   While traditional unit-level TDD is inefficient as a standard daily workflow in distributed Agent systems, it must be resurrected as an **"Emergency Breakout Tool"**.
*   **Free from Spring Coupling:** Provide ultra-lightweight "Library-mode" memory stubs within `agent-runtime-core`. When an AI is stuck in an SDD iteration loop, force it into a micro-mode: spin up a millisecond-level pure-function TDD test without external containers. Use granular, deterministic Red-Green feedback to physically drag the AI out of its high-dimensional hallucination swamp.

#### 2.3 Immediate TCK Activation via Reference Implementation (RI)
*   **Manifesting the Abstract:** Do not wait for W2's Postgres implementation to build the Technology Compatibility Kit (TCK).
*   **Action Item:** Immediately activate the reserved `agent-runtime-tck` module. Elevate the existing `InMemoryCheckpointer` and `InMemoryRunRegistry` to serve as the **Reference Implementation (RI)**. Instantiate the SPI contracts into an executable test suite today using memory components. Use "End-to-End Physical Simulation" to crush the cognitive biases induced by pure text-based contracts.

### 3. Conclusion
The future architecture requires not only 78 Bash gates for defense but also a scalpel to pierce AI hallucinations on demand. Using SDD as the macroscopic skeleton, and Telemetry-First + Micro-TDD as the breakout engines, will forge true architectural rationality.