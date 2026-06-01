package com.huawei.ascend.service.engine.spi;

import com.huawei.ascend.service.engine.event.EngineCommandEvent;

/**
 * Abstraction over the engine's internal command queue. Implementations carry
 * command events from producers (the API layer) to the subscriber that drives
 * dispatch. See engine model design §7.1.
 */
public interface EngineQueueGateway {

    /** Publish a command onto the queue. Returns whether it was accepted. */
    boolean publish(EngineCommandEvent event);

    /** Register a consumer to receive published commands. */
    void subscribe(EngineCommandConsumer consumer);
}
