package com.huawei.ascend.bus.forwarding.runtime.transport.broker;

import com.huawei.ascend.bus.forwarding.spi.ForwardingFailureCode;

import java.util.Optional;

/**
 * Receiver-side broker SPI: poll a broker for inbound messages and commit / reject
 * them (Stage 26, T4 hybrid).
 *
 * <p>The receiver consumer calls {@link #poll} with its {@code consumerServiceId}
 * (broker consumer-group) and {@code tenantId} (L2 header-tenant-check defense —
 * decision §6.2 ⑤). The implementation returns the next uncommitted message whose
 * header tenantId matches; a message whose header tenantId differs from the poll
 * tenant is never returned (rejected, not committed). The receiver processes the
 * message, then {@link #commit commits} (model B ack-after-consume — at-least-once)
 * or {@link #reject rejects} on failure (not committed → redelivered).
 *
 * <p>Broker product concepts (topic / partition / offset / consumer-group) never
 * escape this port — {@code consumerServiceId} is the broker consumer-group
 * materialised as a plain string, and {@link BrokerInboundMessage} exposes no
 * offset (decision §6.2 ① spirit).
 *
 * <p>Authority: {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-forwarding-runtime-transport-decision.md} (Stage 25 adopted-t4 §4 / §10,
 * Stage 26 broker SPI scaffold).
 */
// scope: forwarding transport.broker — receiver SPI; commit after consume (model B, at-least-once)
public interface BrokerForwardingConsumerPort {

    /**
     * Poll the next uncommitted inbound message for this consumer + tenant.
     *
     * <p>Returns empty when no matching message is available. A message whose
     * header tenantId differs from {@code tenantId} is never returned (L2 defense).
     *
     * @param consumerServiceId the broker consumer-group identifier (inbox dedup key component)
     * @param tenantId the polling tenant (L2 header-tenant-check; cross-tenant messages rejected)
     * @param nowMillisEpoch the poll instant
     * @return the next message, or empty
     */
    Optional<BrokerInboundMessage> poll(String consumerServiceId, String tenantId, long nowMillisEpoch);

    /**
     * Commit a polled message — the receiver processed it successfully (model B
     * ack-after-consume). The message will not be redelivered to this consumer-group.
     *
     * @param message the polled message to commit (carries its in-flight consumerServiceId)
     */
    void commit(BrokerInboundMessage message);

    /**
     * Reject a polled message — the receiver could not process it (e.g. a tenant
     * mismatch surfaced after poll, or a processing failure). The message is NOT
     * committed, so the broker redelivers it (at-least-once); the failure code is
     * recorded for observability. Broker native retry/DLX is OFF (agent-bus retry
     * policy, Stage 14, leads).
     *
     * @param message the polled message to reject (carries its in-flight consumerServiceId)
     * @param code the failure code (non-null)
     */
    void reject(BrokerInboundMessage message, ForwardingFailureCode code);
}
