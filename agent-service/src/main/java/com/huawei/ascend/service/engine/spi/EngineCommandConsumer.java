package com.huawei.ascend.service.engine.spi;

import com.huawei.ascend.service.engine.event.EngineCommandEvent;

/**
 * Consumes a command event pulled from the engine command queue.
 * See engine model design §7.2.
 */
@FunctionalInterface
public interface EngineCommandConsumer {
    void accept(EngineCommandEvent event);
}
