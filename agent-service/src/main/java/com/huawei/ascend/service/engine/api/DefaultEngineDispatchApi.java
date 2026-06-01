package com.huawei.ascend.service.engine.api;

import com.huawei.ascend.service.engine.event.EngineCommandEvent;
import com.huawei.ascend.service.engine.queue.EngineCommandEventFactory;
import com.huawei.ascend.service.engine.spi.EngineQueueGateway;

/**
 * Default {@link EngineDispatchApi}: the inbound entry point for
 * task-centric-control. Each call builds a command event and publishes it onto
 * the engine queue, returning only the enqueue outcome — real execution status
 * is written back later through {@code TaskControlClient} (design §4, §7).
 */
public class DefaultEngineDispatchApi implements EngineDispatchApi {

    private final EngineCommandEventFactory commandEventFactory;
    private final EngineQueueGateway queueGateway;

    public DefaultEngineDispatchApi(EngineCommandEventFactory commandEventFactory, EngineQueueGateway queueGateway) {
        this.commandEventFactory = commandEventFactory;
        this.queueGateway = queueGateway;
    }

    @Override
    public EnqueueEngineStatus enqueueExecution(EnqueueEngineExecutionRequest request) {
        return publish(commandEventFactory.execute(request));
    }

    @Override
    public EnqueueEngineStatus enqueueResume(EnqueueEngineResumeRequest request) {
        return publish(commandEventFactory.resume(request));
    }

    @Override
    public EnqueueEngineStatus enqueueCancel(EnqueueEngineCancelRequest request) {
        return publish(commandEventFactory.cancel(request));
    }

    private EnqueueEngineStatus publish(EngineCommandEvent event) {
        boolean accepted = queueGateway.publish(event);
        return accepted ? EnqueueEngineStatus.SUCCESS : EnqueueEngineStatus.FAILED;
    }
}
