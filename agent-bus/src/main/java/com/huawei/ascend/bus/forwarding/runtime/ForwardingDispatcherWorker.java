package com.huawei.ascend.bus.forwarding.runtime;

import com.huawei.ascend.bus.forwarding.spi.ForwardingDeliveryPort;
import com.huawei.ascend.bus.forwarding.spi.ForwardingDeliveryResult;
import com.huawei.ascend.bus.forwarding.spi.ForwardingLeaseException;
import com.huawei.ascend.bus.forwarding.spi.ForwardingOutboxClaimPort;
import com.huawei.ascend.bus.forwarding.spi.ForwardingOutboxPort;
import com.huawei.ascend.bus.forwarding.spi.ForwardingOutboxRecord;

import java.util.List;
import java.util.Objects;

/**
 * Minimal dispatcher worker that drives claimed outbox records to a terminal
 * state through the abstract delivery port (Stage 8 plan §3 slice 5).
 *
 * <p>This is the claim / deliver / ack / retry half of the forwarding lifecycle,
 * kept separate from {@code ForwardingDispatcher} (the accept / enqueue gateway
 * role) per MI8-003. A single synchronous {@link #runOnce} tick:
 * <ol>
 *   <li>claims due records via {@link ForwardingOutboxClaimPort#claimDue} (each
 *       already atomically transitioned to DISPATCHING and leased to the worker);</li>
 *   <li>delivers each via {@link ForwardingDeliveryPort#deliver}, consuming only
 *       the opaque {@code routeHandle} (never a physical endpoint);</li>
 *   <li>maps the {@link ForwardingDeliveryResult} to the matching outbox state
 *       transition — ACKED / RETRY_SCHEDULED / DLQ / EXPIRED — carrying the
 *       caller's {@code leaseOwner} so the mutation is lease-owner guarded
 *       (Stage 9, MI9-001): a record reclaimed by another worker between claim
 *       and ack is rejected, not mutated. A rejected mutation raises
 *       {@link ForwardingLeaseException}; the worker treats it as "skip this
 *       record" (Stage 10, MI10-001) — counted as {@code skipped} and the tick
 *       continues, so one reclaimed record never aborts the whole tick.</li>
 * </ol>
 *
 * <p>The worker holds no threads, no scheduler, no registry, no transport. Real
 * polling cadence, threading, backpressure and a concrete delivery binding are
 * deferred to a later stage; Stage 8 ships this skeleton so the ACK / RETRY /
 * DLQ / EXPIRED paths can be exercised with a fake delivery port. The worker
 * never writes Task execution state.
 *
 * <p>Lease renewal (Stage 10, MI10-002): a {@code deliver} that outlives the
 * lease TTL would lose the lease and fail the ack. The worker carries a
 * {@link DispatchLeasePolicy}; before each delivery, if the remaining lease TTL
 * is below the policy threshold it calls {@link ForwardingOutboxClaimPort#renewLease}
 * to extend the lease. A renew that returns {@code false} (reclaimed / not
 * DISPATCHING) is treated exactly like a {@link ForwardingLeaseException}: the
 * record is skipped, not delivered. The in-memory harness checks owner, not
 * lease expiry, so "renew-or-lose-the-ack" is encoded as a SQL contract in
 * {@code forwarding-persistence.md §7.2}, not asserted in-memory.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §3/§4.1};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §5}.
 */
public final class ForwardingDispatcherWorker {

    private final ForwardingOutboxClaimPort claimPort;
    private final ForwardingOutboxPort outboxPort;
    private final ForwardingDeliveryPort deliveryPort;
    private final DispatchLeasePolicy leasePolicy;

    public ForwardingDispatcherWorker(ForwardingOutboxClaimPort claimPort,
                                      ForwardingOutboxPort outboxPort,
                                      ForwardingDeliveryPort deliveryPort) {
        this(claimPort, outboxPort, deliveryPort, DispatchLeasePolicy.DISABLED);
    }

    public ForwardingDispatcherWorker(ForwardingOutboxClaimPort claimPort,
                                      ForwardingOutboxPort outboxPort,
                                      ForwardingDeliveryPort deliveryPort,
                                      DispatchLeasePolicy leasePolicy) {
        this.claimPort = Objects.requireNonNull(claimPort, "claimPort is required");
        this.outboxPort = Objects.requireNonNull(outboxPort, "outboxPort is required");
        this.deliveryPort = Objects.requireNonNull(deliveryPort, "deliveryPort is required");
        this.leasePolicy = Objects.requireNonNull(leasePolicy, "leasePolicy is required");
    }

    /**
     * Run one dispatch tick for a single tenant.
     *
     * <p>The worker's {@link DispatchLeasePolicy} (set at construction) decides
     * whether a short remaining lease is renewed before delivery (Stage 10,
     * MI10-002); a record whose renew or ack is rejected by the lease guard is
     * skipped, not delivered, and the tick continues.
     *
     * @param tenantId             tenant scope of the tick (Rule R-C.c)
     * @param nowMillisEpoch       the tick instant
     * @param limit                max records to claim this tick ({@code > 0})
     * @param leaseOwner           identity of this worker instance
     * @param leaseUntilMillisEpoch instant until which claimed leases are exclusive
     * @return a summary of how many records were claimed and how each resolved
     */
    public DispatchTickResult runOnce(String tenantId, long nowMillisEpoch, int limit,
                                      String leaseOwner, long leaseUntilMillisEpoch) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        Objects.requireNonNull(leaseOwner, "leaseOwner is required");
        if (leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }

        List<ForwardingOutboxRecord> claimed =
                claimPort.claimDue(tenantId, nowMillisEpoch, limit, leaseOwner, leaseUntilMillisEpoch);

        int acked = 0;
        int retried = 0;
        int dlqd = 0;
        int expired = 0;
        int skipped = 0;
        for (ForwardingOutboxRecord record : claimed) {
            try {
                // Stage 10 (MI10-002): if the remaining lease TTL is below the policy
                // threshold, renew before delivering so a long deliver does not outlive
                // the lease. A failed renew (reclaimed / not DISPATCHING) is treated
                // like a lease exception — skip, do not deliver.
                if (leasePolicy.renewBeforeExpiryMillis() > 0) {
                    long remaining = leaseUntilMillisEpoch - nowMillisEpoch;
                    if (remaining < leasePolicy.renewBeforeExpiryMillis()) {
                        long extendedUntil = leaseUntilMillisEpoch + leasePolicy.leaseExtensionMillis();
                        if (!claimPort.renewLease(record.messageId(), tenantId, leaseOwner, extendedUntil)) {
                            skipped++;
                            continue;
                        }
                    }
                }
                ForwardingDeliveryResult result = deliveryPort.deliver(record, nowMillisEpoch);
                switch (result.outcome()) {
                    case ACKED -> {
                        outboxPort.markAcked(record.messageId(), tenantId, leaseOwner);
                        acked++;
                    }
                    case RETRY_SCHEDULED -> {
                        outboxPort.scheduleRetry(record.messageId(), tenantId, leaseOwner,
                                result.failureCode(), result.nextAttemptAtMillisEpoch());
                        retried++;
                    }
                    case DLQ -> {
                        outboxPort.moveToDlq(record.messageId(), tenantId, leaseOwner,
                                result.failureCode());
                        dlqd++;
                    }
                    case EXPIRED -> {
                        outboxPort.markExpired(record.messageId(), tenantId, leaseOwner);
                        expired++;
                    }
                }
            } catch (ForwardingLeaseException e) {
                // Stage 10 (MI10-001): the lease guard rejected this record — it was
                // reclaimed by another worker, or the lease is no longer held / not
                // DISPATCHING. Skip it: the record's true owner (or the next reclaim)
                // drives it forward. The tick continues with the remaining records.
                skipped++;
            }
        }
        return new DispatchTickResult(claimed.size(), acked, retried, dlqd, expired, skipped);
    }

    /** Immutable summary of one dispatch tick. */
    public record DispatchTickResult(int claimed, int acked, int retried, int dlqd, int expired,
                                     int skipped) {
        public DispatchTickResult {
            if (claimed < 0 || acked < 0 || retried < 0 || dlqd < 0 || expired < 0 || skipped < 0) {
                throw new IllegalArgumentException("tick counts must be non-negative");
            }
            if (claimed != acked + retried + dlqd + expired + skipped) {
                throw new IllegalArgumentException(
                        "tick counts must be self-consistent: claimed must equal "
                        + "acked + retried + dlqd + expired + skipped");
            }
        }
    }

    /**
     * Lease-renewal policy for a dispatch tick (Stage 10, MI10-002). Governs when
     * {@link ForwardingDispatcherWorker} refreshes a claimed record's lease before
     * delivery, so a long {@code deliver} does not outlive the lease TTL.
     *
     * <p>Before each delivery, if the remaining lease TTL (leaseUntil - now) is
     * below {@code renewBeforeExpiryMillis}, the worker calls
     * {@link ForwardingOutboxClaimPort#renewLease} to extend the lease by
     * {@code leaseExtensionMillis}. A renew returning {@code false} (the worker
     * no longer holds the lease — reclaimed / not DISPATCHING) is treated like a
     * {@link ForwardingLeaseException}: the record is skipped, not delivered.
     *
     * <p>Note: the in-memory harness checks owner, not lease expiry, so it cannot
     * reproduce the JDBC {@code WHERE lease_until > now()} guard — the
     * "renew-or-lose-the-ack" semantics are a SQL contract
     * ({@code forwarding-persistence.md §7.2}), not an in-memory assertion.
     */
    public record DispatchLeasePolicy(long renewBeforeExpiryMillis, long leaseExtensionMillis) {
        public DispatchLeasePolicy {
            if (renewBeforeExpiryMillis < 0) {
                throw new IllegalArgumentException("renewBeforeExpiryMillis must be >= 0");
            }
            if (leaseExtensionMillis <= 0) {
                throw new IllegalArgumentException("leaseExtensionMillis must be > 0");
            }
        }

        /** No renewal: the worker relies on the caller's leaseUntil for the whole tick. */
        public static final DispatchLeasePolicy DISABLED = new DispatchLeasePolicy(0, 1);
    }
}
