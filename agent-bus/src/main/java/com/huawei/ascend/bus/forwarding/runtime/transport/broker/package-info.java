/**
 * Broker transport adapter for the C3 forwarding substrate (Stage 26, T4 hybrid).
 *
 * <p>Stage 25 adopted T4 hybrid (outbox + broker): the outbox stays the durable /
 * transactional / auditable / RLS-protected layer (Stage 12 + Stage 24), and a
 * broker carries messages from the relay (sender-side) to the receiver
 * (consumer-pull — backpressure owned by the consumer, MQ is just one carrier).
 *
 * <p>This package is the broker-agnostic SPI scaffold: {@link com.huawei.ascend.bus.forwarding.runtime.transport.broker.BrokerForwardingRelayPort}
 * (relay: outbox record → broker produce) and {@link com.huawei.ascend.bus.forwarding.runtime.transport.broker.BrokerForwardingConsumerPort}
 * (receiver: broker poll → commit/reject), plus the broker-agnostic message /
 * outcome / config types. A concrete broker adapter (Stage 27+ RocketMQ PoC) lives
 * here too; Stage 26 ships the in-memory test double only.
 *
 * <p><b>Governance.</b> Decision §6.1 item 1 (concrete broker client) is lifted for
 * THIS subpackage only (Stage 25), mirroring how Stage 12 confines Spring/JDBC to
 * {@code persistence.jdbc} and Stage 15 confines the A2A SDK to {@code transport.a2a}.
 * Decision §6.2 ① spirit holds: broker product concepts (topic / partition / offset /
 * consumer-group) never leak outside this package — they map onto forwarding SPI
 * concepts (topic→routeHandle via resolver, partition-key→messageId hash,
 * consumer-group→consumerServiceId, offset→adapter-internal commit). §6.2 ②③④⑤
 * are unchanged (no payload body / token stream / Task state in the broker message;
 * payloadRef rides as a header; cross-tenant is explicitly rejected).
 *
 * <p>Authority: {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-forwarding-runtime-transport-decision.md} (Stage 25 adopted-t4);
 * {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-forwarding-runtime-decision.md} §6.1/§6.2.
 */
package com.huawei.ascend.bus.forwarding.runtime.transport.broker;
